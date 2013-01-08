package scala.slick
package additions

import lifted._
import driver._
import profile._

import scala.reflect.runtime.currentMirror
import reflect.ClassTag

sealed trait Entity[K, +A] {
  def value: A

  def isSaved: Boolean

  def map[B >: A](f: A => B): Entity[K, B]

  def duplicate = new KeylessEntity[K, A](value)
}
class KeylessEntity[K, +A](val value: A) extends Entity[K, A] {
  final def isSaved = false

  override def equals(that: Any) = this eq that.asInstanceOf[AnyRef]

  def map[B >: A](f: A => B): KeylessEntity[K, B] = new KeylessEntity[K, B](f(value))

  override def toString = s"KeylessEntity($value)"
}
sealed trait KeyedEntity[K, +A] extends Entity[K, A] {
  def key: K

  def map[B >: A](f: A => B): ModifiedEntity[K, B] = ModifiedEntity[K, B](key, f(value))
}
case class SavedEntity[K, +A](key: K, value: A) extends KeyedEntity[K, A] {
  final def isSaved = true
}
case class ModifiedEntity[K, +A](key: K, value: A) extends KeyedEntity[K, A] {
  final def isSaved = false
}

trait KeyedTableComponent extends JdbcDriver {
  trait CanSetLookup[K, A] {
    def apply[E <: KeyedEntity[K, A]](e: E): E
  }

  abstract class KeyedTable[K, A](tableName: String)(implicit m: ast.BaseTypedType[K] with simple.ColumnType[K]) extends Table[A](tableName) { keyedTable =>
    def keyColumnName = "id"
    def keyColumnOptions = List(O.PrimaryKey, O.NotNull, O.AutoInc)
    def key = column[K](keyColumnName, keyColumnOptions: _*)

    def lookup: Column[Lookup] = column[Lookup](keyColumnName, keyColumnOptions: _*)

    case class Lookup(key: K) extends additions.Lookup[Option[A], simple.Session] {
      import simple._
      def query: Query[simple.KeyedTable[K, A], A] = {
        Query(KeyedTable.this).filter(_.key is key)
      }
      def compute(implicit session: simple.Session): Option[A] = query.firstOption
    }

    class OneToMany[E >: B, B, TB <: simple.Table[B]](
      private[KeyedTable] val otherTable: TB with simple.Table[B],
      private[KeyedTable] val thisLookup: Option[Lookup]
    )(
      private[KeyedTable] val column: TB => Column[Lookup],
      private[KeyedTable] val setLookup: Lookup => E => E
    ) extends additions.SeqLookup[E, simple.Session] with DiffSeq[E, OneToMany[E, B, TB]] {

      protected val isCopy = false

      protected def copy(items: Seq[Handle[E]]) = new OneToMany[E, B, TB](otherTable, thisLookup)(column, setLookup) {
        override val initialItems = OneToMany.this.initialItems
        override val currentItems = items
        override def apply()(implicit session: simple.Session) = currentItems map (_.value)
        override val isCopy = true
      }

      def withLookup(lookup: Lookup) = if(isCopy) this map setLookup(lookup) else {
        val f = setLookup(lookup)
        new OneToMany[E, B, TB](otherTable, Some(lookup))(column, setLookup) {
          cached = OneToMany.this.cached
          override def currentItems = super.currentItems map (_ map f)
        }
      }

      import simple._

      def query: Query[TB, B] =
        Query(KeyedTable.this)
          .filter(t => thisLookup map (t.key is _.key) getOrElse ConstColumn(false))
          .flatMap{ t =>
            Query(otherTable).filter(column(_) is t.asInstanceOf[KeyedTable.this.type].lookup)
          }

      def compute(implicit session: Session): Seq[E] = query.list
    }

    implicit class OneToManyEntSave[KB, B, TB <: simple.EntityTable[KB, B]](
      oneToMany: OneToMany[TB#Ent, TB#KEnt, TB]
    )(
      implicit csl: CanSetLookup[KB, B] = null
    ) {
      val setEntityLookup = Option(csl)

      import simple._
      import oneToMany._

      def saved(implicit session: Session): OneToMany[TB#Ent, TB#KEnt, TB] = {
        initialItems filterNot isRemoved map (_.value) foreach {
          case e: KeyedEntity[KB, B] => otherTable.delete(e)
          case _ =>
        }
        val items = currentItems map { h =>
          val saved = h.value match {
            case e: SavedEntity[KB, B] => e
            case e => otherTable save e
          }
          setEntityLookup map (_(saved)) getOrElse saved
        }
        new OneToMany[TB#Ent, TB#KEnt, TB](otherTable, thisLookup)(oneToMany.column, setLookup) {
          cached = Some(items)
        }
      }
    }

    def OneToMany[B, TB <: simple.Table[B]](
      otherTable: TB with simple.Table[B], lookup: Option[Lookup]
    )(
      column: TB => Column[Lookup], setLookup: Lookup => B => B, initial: Seq[B] = null
    ) = new OneToMany[B, B, TB](otherTable, lookup)(column, setLookup) {
      cached = Option(initial)
    }

    def OneToManyEnt[KB, B, TB <: simple.EntityTable[KB, B]](
      otherTable: TB with simple.EntityTable[KB, B], lookup: Option[Lookup]
    )(
      column: TB => Column[Lookup], setLookup: Lookup => B => B, initial: Seq[TB#Ent] = null
    ) = new OneToMany[TB#Ent, TB#KEnt, TB](otherTable, lookup)(column, l => _.map(setLookup(l))) {
      cached = Option(initial)
    }

    type OneToManyEnt[KB, B, TB <: simple.EntityTable[KB, B]] = OneToMany[TB#Ent, TB#KEnt, TB]

    implicit def lookupMapper: simple.ColumnType[Lookup] with ast.BaseTypedType[Lookup] =
      simple.MappedColumnType.base[Lookup, K](_.key, Lookup(_))
  }

  abstract class EntityTable[K, A](tableName: String)(implicit m: ast.BaseTypedType[K] with simple.ColumnType[K]) extends KeyedTable[K, KeyedEntity[K, A]](tableName) {
    type Key   = K
    type Value = A
    type Ent   = Entity[K, A]
    type KEnt  = KeyedEntity[K, A]

    def Ent(a: A) = new KeylessEntity[K, A](a)

    case class Mapping(forInsert: ColumnBase[A], * : ColumnBase[KEnt])
    object Mapping {
      implicit def fromColumn(c: Column[A]) =
        Mapping(
          c,
          key ~ c <> (
            SavedEntity(_, _),
            { case ke: KEnt => Some((ke.key, ke.value)) }
          )
        )
      implicit def fromProjection3[T1,T2,T3](p: Projection3[T1,T2,T3])(implicit ev: A =:= (T1, T2, T3)) =
        p <-> (_ => Function.untupled(x => x.asInstanceOf[A]), x => Some(x))
    }

    def mapping: Mapping

    def forInsert: ColumnBase[A] = mapping.forInsert

    def * = mapping.*

    def insert(v: A)(implicit session: simple.Session): SavedEntity[K, A] = insert(Ent(v))
    def insert(e: Entity[K, A])(implicit session: simple.Session): SavedEntity[K, A] = {
      import simple._
      e match {
        case ke: KEnt => SavedEntity(* returning key insert ke, ke.value)
        case ke: Ent  => SavedEntity(forInsert returning key insert ke.value, ke.value)
      }
    }
    def update(ke: KEnt)(implicit session: simple.Session): SavedEntity[K, A] = {
      import simple._
      Query(this).where(_.key is ke.key).map(_.forInsert) update ke.value
      SavedEntity(ke.key, ke.value)
    }
    def save(e: Entity[K, A])(implicit session: simple.Session): SavedEntity[K, A] = {
      e match {
        case ke: KEnt => update(ke)
        case ke: Ent  => insert(ke)
      }
    }

    def delete(ke: KEnt)(implicit session: simple.Session) = {
      import simple._
      Query(this).filter(_.key is ke.key).delete
    }

    //TODO EntityMapping{6..22}
    trait EntityMapping[Ap, Unap] {
      type _Ap = Ap
      type _Unap = Unap
      def <->(ap: Option[K] => Ap, unap: Unap): Mapping
      def <->(ap: Ap, unap: Unap): Mapping = <->(_ => ap, unap)
    }
    implicit class EntityMapping2[T1, T2](val p: Projection2[T1, T2]) extends EntityMapping[(T1, T2) => A, A => Option[(T1, T2)]] {
      def <->(ap: Option[K] => _Ap, unap: _Unap) = Mapping(
        p <> (ap(None), unap),
        (key ~ p).<>[KEnt](
          (t: (K, T1, T2)) => SavedEntity(t._1, ap(Some(t._1))(t._2, t._3)),
          { ke: KEnt => unap(ke.value) map (t => (ke.key, t._1, t._2)) }
        )
      )
    }
    implicit class EntityMapping3[T1, T2, T3](val p: Projection3[T1, T2, T3]) extends EntityMapping[(T1, T2, T3) => A, A => Option[(T1, T2, T3)]] {
      def <->(ap: Option[K] => _Ap, unap: _Unap) = Mapping(
        p <> (ap(None), unap),
        (key ~ p).<>[KEnt](
          (t: (K, T1, T2, T3)) => SavedEntity(t._1, ap(Some(t._1))(t._2, t._3, t._4)),
          { ke: KEnt => unap(ke.value) map (t => (ke.key, t._1, t._2, t._3)) }
        )
      )
    }
    implicit class EntityMapping4[T1, T2, T3, T4](val p: Projection4[T1, T2, T3, T4]) extends EntityMapping[(T1, T2, T3, T4) => A, A => Option[(T1, T2, T3, T4)]] {
      def <->(ap: Option[K] => _Ap, unap: _Unap) = Mapping(
        p <> (ap(None), unap),
        (key ~ p).<>[KEnt](
          (t: (K, T1, T2, T3, T4)) => SavedEntity(t._1, ap(Some(t._1))(t._2, t._3, t._4, t._5)),
          { ke: KEnt => unap(ke.value) map (t => (ke.key, t._1, t._2, t._3, t._4)) }
        )
      )
    }
    implicit class EntityMapping5[T1, T2, T3, T4, T5](val p: Projection5[T1, T2, T3, T4, T5]) extends EntityMapping[(T1, T2, T3, T4, T5) => A, A => Option[(T1, T2, T3, T4, T5)]] {
      def <->(ap: Option[K] => _Ap, unap: _Unap) = Mapping(
        p <> (ap(None), unap),
        (key ~ p).<>[KEnt](
          (t: (K, T1, T2, T3, T4, T5)) => SavedEntity(t._1, ap(Some(t._1))(t._2, t._3, t._4, t._5, t._6)),
          { ke: KEnt => unap(ke.value) map (t => (ke.key, t._1, t._2, t._3, t._4, t._5)) }
        )
      )
    }
  }

  trait SimpleQL extends super.SimpleQL {
    type KeyedTable[K, A] = KeyedTableComponent.this.KeyedTable[K, A]
    type EntityTable[K, A] = KeyedTableComponent.this.EntityTable[K, A]
  }
  override val simple: SimpleQL = new SimpleQL {}
}

trait NamingDriver extends KeyedTableComponent {
  abstract class KeyedTable[K, A](tableName: String)(implicit m: ast.BaseTypedType[K] with simple.ColumnType[K]) extends super.KeyedTable[K, A](tableName) {
    def this()(implicit m: ast.BaseTypedType[K] with simple.ColumnType[K]) =
     this(currentMirror.classSymbol(Class.forName(Thread.currentThread.getStackTrace()(2).getClassName)).name.decoded)(m)

    def column[C](options: ast.ColumnOption[C]*)(implicit tm: ast.TypedType[C]): Column[C] =
      column[C](scala.reflect.NameTransformer.decode(Thread.currentThread.getStackTrace()(2).getMethodName), options: _*)
  }
  trait SimpleQL extends super.SimpleQL {
    //override type KeyedTable[A, K] = NamingDriver.this.KeyedTable[A, K]
  }
  override val simple: SimpleQL = new SimpleQL {}
}

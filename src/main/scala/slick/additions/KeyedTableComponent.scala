package slick
package additions

import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, implicitConversions}
import scala.reflect.{ClassTag, classTag}

import slick.additions.entity._
import slick.ast._
import slick.jdbc.JdbcProfile
import slick.lifted.{MappedProjection, RepShape, ShapedValue}


trait KeyedTableComponentBase {
  val profile: JdbcProfile

  import profile.api._


  implicit class lookupOps[K, V, A, T](self: Lookup[K, V])(implicit lookups: Lookups[K, V, A, T]) {
    def query: Query[T, A, Seq] = lookups.lookupQuery(self)
    def fetched[R](implicit ec: ExecutionContext): DBIO[Lookup[K, V]] =
      query.result.headOption.map(_.map(a => SavedEntity(self.key, lookups.lookupValue(a))) getOrElse self)
    def apply[R]()(implicit ec: ExecutionContext): DBIO[Lookup[K, V]] =
      self.foldLookup(_ => fetched, ke => DBIO.successful(ke))
  }

  trait Lookups[K, V, A, T] {
    def lookupQuery(lookup: Lookup): Query[T, A, Seq]
    def lookupValue(a: A): V
    type Lookup = entity.Lookup[K, V]
    object Lookup {
      def apply(key: K): Lookup = EntityKey(key)
      def apply(key: K, precache: V): Lookup = SavedEntity(key, precache)
      def apply(ke: KeyedEntity[K, V]): Lookup = ke
    }
  }

  implicit def lookupIsomorphism[K: BaseColumnType, A]: Isomorphism[Lookup[K, A], K] =
    new Isomorphism(_.key, EntityKey(_))

  trait KeyedTableBase {
    keyedTable: Table[_] =>
    type Key
    def keyColumnName = "id"
    def keyColumnOptions = List(O.PrimaryKey, O.AutoInc)
    def key = column[Key](keyColumnName, keyColumnOptions: _*)
    implicit def keyMapper: TypedType[Key]
  }

  abstract class KeyedTable[K, A](tag: Tag, tableName: String)(implicit val keyMapper: BaseColumnType[K])
    extends Table[A](tag, tableName) with KeyedTableBase {
    type Key = K
  }

  trait EntityTableBase extends KeyedTableBase {
    this: Table[_] =>
    type Value
    type Ent = Entity[Key, Value]
    type KEnt = KeyedEntity[Key, Value]
  }

  abstract class EntityTable[K: BaseColumnType, V](tag: Tag, tableName: String)
    extends KeyedTable[K, KeyedEntity[K, V]](tag, tableName) with EntityTableBase {
    type Value = V
    def Ent(v: Value) = new KeylessEntity[Key, Value](v)

    def tableQuery: Query[EntityTable[K, V], KEnt, Seq]
    implicit class MapProj[Value](value: Value) {
      def <->[R: ClassTag, Unpacked](construct: Option[K] => Unpacked => R, extract: R => Option[Unpacked])
                                    (implicit shape: Shape[_ <: FlatShapeLevel, Value, Unpacked, _]): MappedProj[Value, Unpacked, R] =
        new MappedProj[Value, Unpacked, R](value, construct, extract(_).get)(shape, classTag[R])
    }

    implicit class MapProjShapedValue[T, U](v: ShapedValue[T, U]) {
      def <->[R: ClassTag](construct: Option[K] => U => R, extract: R => Option[U]): MappedProj[T, U, R] =
        new MappedProj[T, U, R](v.value, construct, extract(_).get)(v.shape, classTag[R])
    }

    class MappedProj[Src, Unpacked, MappedAs](val source: Src,
                                              val construct: (Option[K] => Unpacked => MappedAs),
                                              val extract: (MappedAs => Unpacked))
                                             (implicit val shape: Shape[_ <: FlatShapeLevel, Src, Unpacked, _],
                                              tag: ClassTag[MappedAs]) extends Rep[MappedAs] {
      override def toNode: Node = TypeMapping(
        shape.toNode(source),
        MappedScalaType.Mapper(
          v => extract(v.asInstanceOf[MappedAs]),
          v => construct(None)(v.asInstanceOf[Unpacked]),
          None
        ),
        tag
      )

      def encodeRef(path: Node): MappedProj[Src, Unpacked, MappedAs] =
        new MappedProj[Src, Unpacked, MappedAs](source, construct, extract)(shape, tag) {
          override def toNode = path
        }

      def ~:(kc: Rep[K]): MappedProjection[KeyedEntity[K, MappedAs], (K, Unpacked)] = {
        val ksv = kc.shaped
        val ssv: ShapedValue[Src, Unpacked] = source.shaped
        (ksv zip ssv).<>[KeyedEntity[K, MappedAs]](
          { case (k, v) => KeyedEntity(k, construct(Some(k))(v)) },
          ke => Some((ke.key, extract(ke.value))))
      }
    }

    object MappedProj {
      implicit class IdentityProj[V2, P: ClassTag](value: V2)(implicit shape: Shape[_ <: FlatShapeLevel, V2, P, _])
        extends MappedProj[V2, P, P](value, _ => identity[P], identity[P])(shape, classTag[P])
    }

    def mapping: MappedProj[_, _, V]

    private def all: MappedProjection[KeyedEntity[K, V], _ <: (K, _)] =
      key ~: mapping

    def * = all

    def lookup = LookupRep[K, V](key <> (EntityKey.apply, lookup => Some(lookup.key)))
  }

  class KeyedTableQueryBase[K: BaseColumnType, A, T <: KeyedTable[K, A]](cons: Tag => (T with KeyedTable[K, A]))
    extends TableQuery[T](cons) {

    type Key = K
    type Lookup

    class OneToMany[K2, V2, T2 <: EntityTable[K2, V2]](private[KeyedTableQueryBase] val otherTable: EntTableQuery[K2, V2, T2],
                                                       private[KeyedTableQueryBase] val thisLookup: Option[Lookup])
                                                      (private[KeyedTableQueryBase] val column: T2 => Rep[Option[Lookup]],
                                                       private[KeyedTableQueryBase] val setLookup: Option[Lookup] => V2 => V2)
                                                      (val items: Seq[Entity[K2, V2]] = Nil,
                                                       val isFetched: Boolean = false)
                                                      (implicit tt: BaseTypedType[Lookup]) {
      type BEnt = Entity[K2, V2]

      def values = items map (_.value)

      private def thisTable = KeyedTableQueryBase.this

      override def equals(o: Any) = o match {
        case that: OneToMany[_, _, _] =>
          this.thisTable == that.thisTable &&
            this.otherTable == that.otherTable &&
            this.column(this.otherTable.baseTableRow).toNode == that.column(that.otherTable.baseTableRow).toNode &&
            this.items.toSet == that.items.toSet
        case _                        => false
      }

      def copy(items: Seq[BEnt], isFetched: Boolean = isFetched) =
        new OneToMany[K2, V2, T2](otherTable, thisLookup)(column, setLookup)(items, isFetched)

      def map(f: Seq[BEnt] => Seq[BEnt]) = copy(f(items))

      def withLookup(lookup: Option[Lookup]): OneToMany[K2, V2, T2] =
        new OneToMany[K2, V2, T2](otherTable, lookup)(column, setLookup)(
          items map { e => e.map(setLookup(lookup)) },
          isFetched = isFetched
        )

      def saved(implicit ec: ExecutionContext): DBIO[OneToMany[K2, V2, T2]] = {
        val deleteAction =
          if (!isFetched) DBIO.successful(())
          else {
            val dq = deleteQuery(items.collect { case ke: KeyedEntity[K2, V2] => ke.key })
            dq.delete
          }
        val saveActions = DBIO.sequence(items map { e: Entity[K2, V2] =>
          if (e.isSaved) DBIO.successful(e)
          else otherTable save e
        })
        (deleteAction >> saveActions)
          .map(xs => copy(xs, isFetched = true))
      }

      def query: Query[T2, KeyedEntity[K2, V2], Seq] = {
        def ot = otherTable
        thisLookup match {
          case None     =>
            ot filter (_ => LiteralColumn(false))
          case Some(lu) =>
            ot filter (column(_) === lu)
        }
      }

      def deleteQuery(keep: Seq[K2]) =
        query.filter { t: EntityTable[K2, V2] =>
          implicit def tm: BaseColumnType[K2] = t.keyMapper
          !(t.key inSet keep)
        }

      def fetched(implicit ec: ExecutionContext) = query.result.map(copy(_, isFetched = true))

      override def toString = s"${KeyedTableQueryBase.this.getClass.getSimpleName}.OneToMany($items)"
    }

    def OneToMany[K2, V2, T2 <: EntityTable[K2, V2]](otherTable: EntTableQuery[K2, V2, T2], lookup: Option[Lookup])
                                                    (column: T2 => Rep[Option[Lookup]],
                                                     setLookup: Option[Lookup] => V2 => V2,
                                                     initial: Seq[Entity[K2, V2]] = null)
                                                    (implicit tt: BaseTypedType[Lookup]): OneToMany[K2, V2, T2] = {
      val init = Option(initial)
      new OneToMany[K2, V2, T2](otherTable, lookup)(column, setLookup)(init getOrElse Nil, init.isDefined)
    }
  }

  class KeyedTableQuery[K: BaseColumnType, A, T <: KeyedTable[K, A]](cons: Tag => (T with KeyedTable[K, A]))
    extends KeyedTableQueryBase[K, A, T](cons) with Lookups[K, A, A, T] {

    override def lookupQuery(lookup: Lookup) = this.filter(_.key === lookup.key)
    override def lookupValue(a: A) = a
    val lookup = (t: T) => LookupRep[K, A](t.key <> (EntityKey.apply, lookup => Some(lookup.key)))
  }

  class EntTableQuery[K: BaseColumnType, V, T <: EntityTable[K, V]](cons: Tag => T with EntityTable[K, V])
    extends KeyedTableQueryBase[K, KeyedEntity[K, V], T](cons) with Lookups[K, V, KeyedEntity[K, V], T] {

    type Value = V
    type Ent = Entity[Key, Value]
    type KEnt = KeyedEntity[Key, Value]
    def Ent(v: V): Ent = new KeylessEntity[Key, Value](v)

    override def lookupQuery(lookup: Lookup) = this.filter(_.key === lookup.key)
    override def lookupValue(a: KeyedEntity[K, V]) = a.value
    def lookup: T => Rep[Lookup] = _.lookup

    trait LookupLens[L] {
      /**
       * Get the field on an entity value that
       * serves as the foreign key lookup
       * for the other table
       */
      def get: V => L
      /**
       * Set an entity value's lookup
       * object, returning a new, modified
       * entity value
       */
      def set: L => V => V
      /**
       * Modify an entity value's lookup
       * relative to itself
       *
       * @param v the entity value
       * @param f a function that transforms a lookup object
       * @return an entity value with the new lookup
       */
      def apply(v: V, f: L => DBIO[L])(implicit ec: ExecutionContext): DBIO[V]
      /**
       * Create a lookup object transformer that
       * synchronizes the lookup object to the database
       * (which may generate new keys)
       */
      def saved(implicit ec: ExecutionContext): L => DBIO[L]
      /**
       * Set the lookup object's key
       */
      def setLookup: K => L => L

      def setLookupAndSave(key: K, v: V)(implicit ec: ExecutionContext) = {
        // val withLookup: L => L = setLookup(key)
        apply(v, setLookup(key) andThen saved)
      }
    }

    case class OneToManyLens[K2, V2, T2 <: EntityTable[K2, V2]](get: V => OneToMany[K2, V2, T2])
                                                               (val set: OneToMany[K2, V2, T2] => V => V)
      extends LookupLens[OneToMany[K2, V2, T2]] {
      def apply(v: V, f: OneToMany[K2, V2, T2] => DBIO[OneToMany[K2, V2, T2]])
               (implicit ec: ExecutionContext): DBIO[V] = {
        val x = f(get(v))
        x map (set(_)(v))
      }
      val setLookup = { key: K => o2m: OneToMany[K2, V2, T2] => o2m withLookup Some(EntityKey(key)) }
      def saved(implicit ec: ExecutionContext) = { otm: OneToMany[K2, V2, T2] => otm.saved }
    }

    def lookupLenses: Seq[LookupLens[_]] = Nil

    private def updateAndSaveLookupLenses(key: K, v: V)(implicit ec: ExecutionContext): DBIO[V] =
      lookupLenses.foldRight(DBIO.successful(v): DBIO[V]) { (clu, v) =>
        v.flatMap(clu.setLookupAndSave(key, _))
      }

    implicit val mappingRepShape: Shape[FlatShapeLevel, T#MappedProj[_, _, V], V, T#MappedProj[_, _, V]] =
      RepShape[FlatShapeLevel, T#MappedProj[_, _, V], V]

    def forInsertQuery[E, C[_]](q: Query[T, E, C]) = q.map(_.mapping)

    def insert(v: V)(implicit ec: ExecutionContext): DBIO[SavedEntity[K, V]] = insert(Ent(v): Ent)

    def insert(e: Ent)(implicit ec: ExecutionContext): DBIO[SavedEntity[K, V]] = {
      // Insert it and get the new or old key
      val k2 = e match {
        case ke: this.KEnt =>
          this returning this.map(_.key: Rep[Key]) forceInsert ke
        case ent: this.Ent =>
          forInsertQuery(this) returning this.map(_.key) += ent.value
      }
      // Apply the key to all child lookups (e.g., OneToMany)
      for {
        k <- k2
        v <- updateAndSaveLookupLenses(k, e.value)
      } yield SavedEntity(k, v)
    }
    def update(ke: KEnt)(implicit ec: ExecutionContext): DBIO[SavedEntity[K, V]] =
      for {
        _ <- forInsertQuery(this.filter(_.key === ke.key)) update ke.value
        v <- updateAndSaveLookupLenses(ke.key, ke.value)
      } yield SavedEntity(ke.key, v)

    def save(e: Entity[K, V])(implicit ec: ExecutionContext): DBIO[SavedEntity[K, V]] = {
      e match {
        case ke: KEnt => update(ke)
        case ke: Ent  => insert(ke)
      }
    }
    def delete(ke: KEnt)(implicit ec: ExecutionContext) = {
      this.filter(_.key === ke.key).delete
    }
  }
}

trait KeyedTableComponent extends JdbcProfile {
  trait API extends super.API with KeyedTableComponentBase {
    override val profile = KeyedTableComponent.this
    type Ent[T <: EntityTableBase] = Entity[T#Key, T#Value]
    type KEnt[T <: EntityTableBase] = KeyedEntity[T#Key, T#Value]
    def Ent[T <: EntityTableBase](value: T#Value) = new KeylessEntity[T#Key, T#Value](value)
  }

  override val api: API = new API {}
}

/*
 * Copyright 2013 Kazuhiro Sera
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package scalikejdbc

import java.util.Locale.{ ENGLISH => en }
import scala.language.reflectiveCalls
import scala.language.experimental.macros
import scala.language.dynamics

/**
 * SQLSyntaxSupport feature
 */
object SQLSyntaxSupportFeature extends LogSupport {

  /**
   * Loaded columns for tables.
   */
  private[scalikejdbc] val SQLSyntaxSupportLoadedColumns = new scala.collection.concurrent.TrieMap[(Any, String), Seq[String]]()

  /**
   * Instant table name validator.
   *
   * Notice: Table name is specified with a String value which might be an input value.
   */
  def verifyTableName(tableNameWithSchema: String): Unit = if (tableNameWithSchema != null) {
    val name = tableNameWithSchema.trim
    val hasWhiteSpace = name.matches(".*\\s+.*")
    val hasSemicolon = name.matches(".*;.*")
    if (hasWhiteSpace || hasSemicolon) {
      log.warn("The table name (${name}) might bring you SQL injection vulnerability.")
    }
  }

}

/**
 * SQLSyntaxSupport feature
 */
trait SQLSyntaxSupportFeature { self: SQLInterpolationFeature =>

  object SQLSyntaxSupport {

    /**
     * Clears all the loaded column names.
     */
    def clearAllLoadedColumns(): Unit = {
      val cache = SQLSyntaxSupportFeature.SQLSyntaxSupportLoadedColumns
      cache.clear()
    }

    /**
     * Clears all the loaded column names for specified connectionPoolName.
     */
    def clearLoadedColumns(connectionPoolName: Any): Unit = {
      val cache = SQLSyntaxSupportFeature.SQLSyntaxSupportLoadedColumns
      cache.keys
        .filter { case (cp, _) => cp == connectionPoolName }
        .foreach { case (cp, table) => cache.remove((cp, table)) }
    }
  }

  /**
   * SQLSyntaxSupport trait. Companion object needs this trait as follows.
   *
   * {{{
   *   case class Member(id: Long, name: Option[String])
   *   object Member extends SQLSyntaxSupport[Member]
   * }}}
   */
  trait SQLSyntaxSupport[A] {

    /**
     * Connection Pool Name. If you use NamedDB, you must override this method.
     */
    def connectionPoolName: Any = ConnectionPool.DEFAULT_NAME

    /**
     * Auto session for current connection pool.
     */
    def autoSession: DBSession = NamedAutoSession(connectionPoolName)

    /**
     * Schema name if exists.
     */
    def schemaName: Option[String] = None

    /**
     * Table name (default: the snake_case name from this companion object's name).
     */
    def tableName: String = {
      val className = getClassSimpleName(this).replaceFirst("\\$$", "").replaceFirst("^.+\\.", "").replaceFirst("^.+\\$", "")
      SQLSyntaxProvider.toColumnName(className, nameConverters, useSnakeCaseColumnName)
    }

    /**
     * Table name with schema name.
     */
    def tableNameWithSchema: String = schemaName.map { schema => s"${schema}.${tableName}" }.getOrElse(tableName)

    private[this] def getClassSimpleName(obj: Any): String = {
      try obj.getClass.getSimpleName
      catch {
        case e: InternalError =>
          // working on the Scala REPL
          val clazz = obj.getClass
          val classOfClazz = clazz.getClass
          val getSimpleBinaryName = classOfClazz.getDeclaredMethods.find(_.getName == "getSimpleBinaryName").get
          getSimpleBinaryName.setAccessible(true)
          getSimpleBinaryName.invoke(clazz).toString
      }
    }

    /**
     * [[scalikejdbc.interpolation.SQLSyntax]] value for table name.
     *
     * Notice: Table name is specified with a String value which might be an input value.
     */
    def table: TableDefSQLSyntax = {
      SQLSyntaxSupportFeature.verifyTableName(tableNameWithSchema)
      TableDefSQLSyntax(tableNameWithSchema)
    }

    /**
     * Column names for this table (default: column names that are loaded from JDBC metadata).
     */
    def columns: Seq[String] = {
      if (columnNames.isEmpty) {
        SQLSyntaxSupportFeature.SQLSyntaxSupportLoadedColumns.getOrElseUpdate((connectionPoolName, tableNameWithSchema), {
          NamedDB(connectionPoolName).getColumnNames(tableNameWithSchema).map(_.toLowerCase(en)) match {
            case Nil => throw new IllegalStateException(
              "No column found for " + tableName + ". If you use NamedDB, you must override connectionPoolName.")
            case cs => cs
          }
        })
      } else columnNames
    }

    /**
     * Clears column names loaded from JDBC metadata.
     */
    def clearLoadedColumns(): Unit = {
      SQLSyntaxSupportFeature.SQLSyntaxSupportLoadedColumns.remove((connectionPoolName, tableNameWithSchema))
    }

    /**
     * If you prefer columnNames than columns, override this method to customize.
     */
    def columnNames: Seq[String] = Nil

    /**
     * True if you need forcing upper column names in SQL.
     */
    def forceUpperCase: Boolean = false

    /**
     * True if you need shortening alias names in SQL.
     */
    def useShortenedResultName: Boolean = true

    /**
     * True if you need to convert filed names to snake_case column names in SQL.
     */
    def useSnakeCaseColumnName: Boolean = true

    /**
     * Delimiter for alias names in SQL.
     */
    def delimiterForResultName = if (forceUpperCase) "_ON_" else "_on_"

    /**
     * Rule to convert field names to column names.
     *
     * {{{
     *   override val nameConverters = Map("^serviceCode$" -> "service_cd")
     * }}}
     */
    def nameConverters: Map[String, String] = Map()

    /**
     * Returns ColumnName provider for this (expected to use for insert/update queries).
     */
    def column: ColumnName[A] = ColumnSQLSyntaxProvider[SQLSyntaxSupport[A], A](this)

    /**
     * Returns SQLSyntax provider for this.
     *
     * {{{
     *   val m = Member.syntax
     *   sql"select ${m.result.*} from ${Member as m}".map(Member(m.resultName)).list.apply()
     *   // select member.id as i_on_member, member.name as n_on_member from member
     * }}}
     */
    def syntax = {
      val _tableName = tableNameWithSchema.replaceAll("\\.", "_")
      val _name = if (forceUpperCase) _tableName.toUpperCase(en) else _tableName
      QuerySQLSyntaxProvider[SQLSyntaxSupport[A], A](this, _name)
    }

    /**
     * Returns SQLSyntax provider for this.
     *
     * {{{
     *   val m = Member.syntax("m")
     *   sql"select ${m.result.*} from ${Member as m}".map(Member(m.resultName)).list.apply()
     *   // select m.id as i_on_m, m.name as n_on_m from member m
     * }}}
     */
    def syntax(name: String) = {
      val _name = if (forceUpperCase) name.toUpperCase(en) else name
      QuerySQLSyntaxProvider[SQLSyntaxSupport[A], A](this, _name)
    }

    /**
     * Returns table name and alias name part in SQL. If alias name and table name are same, alias name will be skipped.
     *
     * {{{
     *   sql"select ${m.result.*} from ${Member.as(m)}"
     * }}}
     */
    def as(provider: QuerySQLSyntaxProvider[SQLSyntaxSupport[A], A]): TableAsAliasSQLSyntax = {
      if (tableName == provider.tableAliasName) { TableAsAliasSQLSyntax(table.value, table.parameters, Some(provider)) }
      else { TableAsAliasSQLSyntax(tableNameWithSchema + " " + provider.tableAliasName, Nil, Some(provider)) }
    }
  }

  /**
   * Table definition (which has alias name) part SQLSyntax
   */
  case class TableAsAliasSQLSyntax private[scalikejdbc] (
    override val value: String,
    override val parameters: Seq[Any] = Vector(),
    resultAllProvider: Option[ResultAllProvider] = None) extends SQLSyntax(value, parameters)

  /**
   * Table definition part SQLSyntax
   */
  case class TableDefSQLSyntax private[scalikejdbc] (
    override val value: String, override val parameters: Seq[Any] = Vector())
      extends SQLSyntax(value, parameters)

  /**
   * SQLSyntax Provider
   */
  trait SQLSyntaxProvider[A] extends Dynamic {
    import SQLSyntaxProvider._

    /**
     * Rule to convert field names to column names.
     */
    val nameConverters: Map[String, String]

    /**
     * True if you need forcing upper column names in SQL.
     */
    val forceUpperCase: Boolean

    /**
     * Delimiter for alias names in SQL.
     */
    val delimiterForResultName: String

    /**
     * True if you need to convert filed names to snake_case column names in SQL.
     */
    val useSnakeCaseColumnName: Boolean

    /**
     * Returns [[scalikejdbc.interpolation.SQLSyntax]] value for the column.
     */
    def c(name: String): SQLSyntax = column(name)

    /**
     * Returns [[scalikejdbc.interpolation.SQLSyntax]] value for the column.
     */
    def column(name: String): SQLSyntax

    /**
     * Returns [[scalikejdbc.interpolation.SQLSyntax]] value for the column which is referred by the field.
     */
    def field(name: String): SQLSyntax = {
      val columnName = {
        if (forceUpperCase) toColumnName(name, nameConverters, useSnakeCaseColumnName).toUpperCase(en)
        else toColumnName(name, nameConverters, useSnakeCaseColumnName)
      }
      c(columnName)
    }

    /**
     * Returns [[scalikejdbc.interpolation.SQLSyntax]] value for the column which is referred by the field.
     */
    def selectDynamic(name: String): SQLSyntax = macro scalikejdbc.SQLInterpolationMacro.selectDynamic[A]

  }

  /**
   * SQLSyntaxProvider companion.
   */
  private[scalikejdbc] object SQLSyntaxProvider {

    private val acronymRegExpStr = "[A-Z]{2,}"
    private val acronymRegExp = acronymRegExpStr.r
    private val endsWithAcronymRegExpStr = "[A-Z]{2,}$"
    private val singleUpperCaseRegExp = """[A-Z]""".r

    /**
     * Returns the snake_case name after applying nameConverters.
     */
    def toColumnName(str: String, nameConverters: Map[String, String], useSnakeCaseColumnName: Boolean): String = {
      val convertersApplied = {
        if (nameConverters != null) nameConverters.foldLeft(str) { case (s, (from, to)) => s.replaceAll(from, to) }
        else str
      }
      if (useSnakeCaseColumnName) {
        val acronymsFiltered = acronymRegExp.replaceAllIn(
          acronymRegExp.findFirstMatchIn(convertersApplied).map { m =>
            convertersApplied.replaceFirst(endsWithAcronymRegExpStr, "_" + m.matched.toLowerCase(en))
          }.getOrElse(convertersApplied), // might end with an acronym
          { m => "_" + m.matched.init.toLowerCase(en) + "_" + m.matched.last.toString.toLowerCase(en) }
        )
        val result = singleUpperCaseRegExp.replaceAllIn(acronymsFiltered, { m => "_" + m.matched.toLowerCase(en) })
          .replaceFirst("^_", "")
          .replaceFirst("_$", "")

        if (str.startsWith("_")) "_" + result
        else if (str.endsWith("_")) result + "_"
        else result

      } else {
        convertersApplied
      }
    }

    /**
     * Returns the shortened name for the name.
     */
    def toShortenedName(name: String, columns: Seq[String]): String = {
      def shorten(s: String): String = s.split("_").map(word => word.take(1)).mkString

      val shortenedName = shorten(toAlphabetOnly(name))
      val shortenedNames = columns.map(c => shorten(toAlphabetOnly(c)))
      if (shortenedNames.count(_ == shortenedName) > 1) {
        val (n, found) = columns.zip(shortenedNames).foldLeft((1, false)) {
          case ((n, found), (column, shortened)) =>
            if (found) {
              (n, found) // alread found
            } else if (column == name) {
              (n, true) // original name is expected
            } else if (shortened == shortenedName) {
              (n + 1, false) // original name is different but shorten name is same
            } else {
              (n, found) // not found yet
            }
        }
        if (!found) throw new IllegalStateException("This must be a library bug.")
        else shortenedName + n
      } else {
        shortenedName
      }
    }

    /**
     * Returns the alias name for the name.
     */
    def toAliasName(originalName: String, support: SQLSyntaxSupport[_]): String = {
      if (support.useShortenedResultName) toShortenedName(originalName, support.columns)
      else originalName
    }

    /**
     * Returns the name which is converted to pure alphabet only.
     */
    private[this] def toAlphabetOnly(name: String): String = {
      val _name = name.filter(c => c.isLetter && c <= 'z' || c == '_')
      if (_name.size == 0) "x" else _name
    }

  }

  /**
   * SQLSyntax provider for column names.
   */
  case class ColumnSQLSyntaxProvider[S <: SQLSyntaxSupport[A], A](support: S) extends SQLSyntaxProvider[A]
      with AsteriskProvider {

    val nameConverters = support.nameConverters
    val forceUpperCase = support.forceUpperCase
    val useSnakeCaseColumnName = support.useSnakeCaseColumnName

    lazy val delimiterForResultName = throw new UnsupportedOperationException("It's a library bug if this exception is thrown.")

    lazy val columns: Seq[SQLSyntax] = support.columns.map { c => if (support.forceUpperCase) c.toUpperCase(en) else c }.map(c => SQLSyntax(c))

    lazy val * : SQLSyntax = SQLSyntax(columns.map(_.value).mkString(", "))

    val asterisk: SQLSyntax = sqls"*"

    def column(name: String): SQLSyntax = columns.find(_.value.equalsIgnoreCase(name)).map { c =>
      SQLSyntax(c.value)
    }.getOrElse {
      throw new InvalidColumnNameException(ErrorMessage.INVALID_COLUMN_NAME +
        s" (name: ${name}, registered names: ${columns.map(_.value).mkString(",")})")
    }

  }

  /**
   * SQLSyntax Provider basic implementation.
   */
  private[scalikejdbc] abstract class SQLSyntaxProviderCommonImpl[S <: SQLSyntaxSupport[A], A](support: S, tableAliasName: String)
      extends SQLSyntaxProvider[A] {

    val nameConverters = support.nameConverters
    val forceUpperCase = support.forceUpperCase
    val useSnakeCaseColumnName = support.useSnakeCaseColumnName
    val delimiterForResultName = support.delimiterForResultName
    lazy val columns: Seq[SQLSyntax] = support.columns.map { c => if (support.forceUpperCase) c.toUpperCase(en) else c }.map(c => SQLSyntax(c))

    def notFoundInColumns(aliasName: String, name: String): InvalidColumnNameException = notFoundInColumns(aliasName, name, columns.map(_.value).mkString(","))

    def notFoundInColumns(aliasName: String, name: String, registeredNames: String): InvalidColumnNameException = {
      new InvalidColumnNameException(ErrorMessage.INVALID_COLUMN_NAME + s" (name: ${aliasName}.${name}, registered names: ${registeredNames})")
    }

  }

  /**
   * SQLSyntax provider for query parts.
   */
  case class QuerySQLSyntaxProvider[S <: SQLSyntaxSupport[A], A](support: S, tableAliasName: String)
      extends SQLSyntaxProviderCommonImpl[S, A](support, tableAliasName)
      with ResultAllProvider
      with AsteriskProvider {

    val result: ResultSQLSyntaxProvider[S, A] = {
      val table = if (support.forceUpperCase) tableAliasName.toUpperCase(en) else tableAliasName
      ResultSQLSyntaxProvider[S, A](support, table)
    }

    override def resultAll: SQLSyntax = result.*

    val resultName: BasicResultNameSQLSyntaxProvider[S, A] = result.name

    lazy val * : SQLSyntax = SQLSyntax(columns.map(c => s"${tableAliasName}.${c.value}").mkString(", "))

    val asterisk: SQLSyntax = SQLSyntax(tableAliasName + ".*")

    def column(name: String): SQLSyntax = columns.find(_.value.equalsIgnoreCase(name)).map { c =>
      SQLSyntax(s"${tableAliasName}.${c.value}")
    }.getOrElse {
      throw new InvalidColumnNameException(ErrorMessage.INVALID_COLUMN_NAME +
        s" (name: ${tableAliasName}.${name}, registered names: ${columns.map(_.value).mkString(",")})")
    }

  }

  /**
   * SQLSyntax provider for result parts.
   */
  case class ResultSQLSyntaxProvider[S <: SQLSyntaxSupport[A], A](support: S, tableAliasName: String)
      extends SQLSyntaxProviderCommonImpl[S, A](support, tableAliasName) {
    import SQLSyntaxProvider._

    val name: BasicResultNameSQLSyntaxProvider[S, A] = BasicResultNameSQLSyntaxProvider[S, A](support, tableAliasName)

    lazy val * : SQLSyntax = SQLSyntax(columns.map { c =>
      val name = toAliasName(c.value, support)
      s"${tableAliasName}.${c.value} as ${name}${delimiterForResultName}${tableAliasName}"
    }.mkString(", "))

    def apply(syntax: SQLSyntax): PartialResultSQLSyntaxProvider[S, A] = PartialResultSQLSyntaxProvider(support, tableAliasName, syntax)

    def column(name: String): SQLSyntax = columns.find(_.value.equalsIgnoreCase(name)).map { c =>
      val name = toAliasName(c.value, support)
      SQLSyntax(s"${tableAliasName}.${c.value} as ${name}${delimiterForResultName}${tableAliasName}")
    }.getOrElse(throw notFoundInColumns(tableAliasName, name))
  }

  case class PartialResultSQLSyntaxProvider[S <: SQLSyntaxSupport[A], A](support: S, aliasName: String, syntax: SQLSyntax)
      extends SQLSyntaxProviderCommonImpl[S, A](support, aliasName) {
    import SQLSyntaxProvider._

    def column(name: String): SQLSyntax = columns.find(_.value.equalsIgnoreCase(name)).map { c =>
      val name = toAliasName(c.value, support)
      SQLSyntax(s"${syntax.value} as ${name}${delimiterForResultName}${aliasName}", syntax.parameters)
    }.getOrElse(throw notFoundInColumns(aliasName, name))
  }

  /**
   * SQLSyntax provider for result names.
   */
  trait ResultNameSQLSyntaxProvider[S <: SQLSyntaxSupport[A], A] extends SQLSyntaxProvider[A] {
    def * : SQLSyntax
    def namedColumns: Seq[SQLSyntax]
    def namedColumn(name: String): SQLSyntax
    def column(name: String): SQLSyntax
  }

  /**
   * Basic Query SQLSyntax Provider for result names.
   */
  case class BasicResultNameSQLSyntaxProvider[S <: SQLSyntaxSupport[A], A](support: S, tableAliasName: String)
      extends SQLSyntaxProviderCommonImpl[S, A](support, tableAliasName) with ResultNameSQLSyntaxProvider[S, A] {
    import SQLSyntaxProvider._

    lazy val * : SQLSyntax = SQLSyntax(columns.map { c =>
      val name = toAliasName(c.value, support)
      s"${name}${delimiterForResultName}${tableAliasName}"
    }.mkString(", "))

    lazy val namedColumns: Seq[SQLSyntax] = support.columns.map { columnName: String =>
      val name = toAliasName(columnName, support)
      SQLSyntax(s"${name}${delimiterForResultName}${tableAliasName}")
    }

    def namedColumn(name: String) = namedColumns.find(_.value.equalsIgnoreCase(name)).getOrElse {
      throw new InvalidColumnNameException(ErrorMessage.INVALID_COLUMN_NAME +
        s" (name: ${name}, registered names: ${namedColumns.map(_.value).mkString(",")})")
    }

    def column(name: String): SQLSyntax = columns.find(_.value.equalsIgnoreCase(name)).map { c =>
      val name = toAliasName(c.value, support)
      SQLSyntax(s"${name}${delimiterForResultName}${tableAliasName}")
    }.getOrElse(throw notFoundInColumns(tableAliasName, name))
  }

  // subquery syntax providers

  object SubQuery {

    def syntax(name: String, resultNames: BasicResultNameSQLSyntaxProvider[_, _]*): SubQuerySQLSyntaxProvider = {
      SubQuerySQLSyntaxProvider(name, resultNames.head.delimiterForResultName, resultNames)
    }

    def syntax(name: String, delimiterForResultName: String, resultNames: BasicResultNameSQLSyntaxProvider[_, _]*): SubQuerySQLSyntaxProvider = {
      SubQuerySQLSyntaxProvider(name, delimiterForResultName, resultNames)
    }

    def syntax(name: String): SubQuerySQLSyntaxProviderBuilder = SubQuerySQLSyntaxProviderBuilder(name)

    def syntax(name: String, delimiterForResultName: String): SubQuerySQLSyntaxProviderBuilder = {
      SubQuerySQLSyntaxProviderBuilder(name, Option(delimiterForResultName))
    }

    case class SubQuerySQLSyntaxProviderBuilder(name: String, delimiterForResultName: Option[String] = None) {
      def include(syntaxProviders: QuerySQLSyntaxProvider[_, _]*): SubQuerySQLSyntaxProvider = {
        SubQuery.syntax(
          name,
          delimiterForResultName.getOrElse(syntaxProviders.head.resultName.delimiterForResultName),
          syntaxProviders.map(_.resultName): _*)
      }
    }

    def as(subquery: SubQuerySQLSyntaxProvider): TableDefSQLSyntax = TableDefSQLSyntax(subquery.aliasName)

  }

  case class SubQuerySQLSyntaxProvider(
    aliasName: String,
    delimiterForResultName: String,
    resultNames: Seq[BasicResultNameSQLSyntaxProvider[_, _]])
      extends ResultAllProvider
      with AsteriskProvider {

    val result: SubQueryResultSQLSyntaxProvider = SubQueryResultSQLSyntaxProvider(aliasName, delimiterForResultName, resultNames)
    val resultName: SubQueryResultNameSQLSyntaxProvider = result.name

    override def resultAll: SQLSyntax = result.*

    lazy val * : SQLSyntax = SQLSyntax(resultNames.map { resultName =>
      resultName.namedColumns.map { c =>
        s"${aliasName}.${c.value}"
      }
    }.mkString(", "))

    val asterisk: SQLSyntax = SQLSyntax(aliasName + ".*")

    def apply(name: SQLSyntax): SQLSyntax = {
      resultNames.find(rn => rn.namedColumns.exists(_.value.equalsIgnoreCase(name.value))).map { rn =>
        SQLSyntax(s"${aliasName}.${rn.namedColumn(name.value).value}")
      }.getOrElse {
        val registeredNames = resultNames.map { rn => rn.columns.map(_.value).mkString(",") }.mkString(",")
        throw new InvalidColumnNameException(ErrorMessage.INVALID_COLUMN_NAME + s" (name: ${name.value}, registered names: ${registeredNames})")
      }
    }

    def apply[S <: SQLSyntaxSupport[A], A](syntax: QuerySQLSyntaxProvider[S, A]): PartialSubQuerySQLSyntaxProvider[S, A] = {
      PartialSubQuerySQLSyntaxProvider(aliasName, delimiterForResultName, syntax.resultName)
    }

  }

  case class SubQueryResultSQLSyntaxProvider(
      aliasName: String,
      delimiterForResultName: String,
      resultNames: Seq[BasicResultNameSQLSyntaxProvider[_, _]]) {

    def name: SubQueryResultNameSQLSyntaxProvider = SubQueryResultNameSQLSyntaxProvider(aliasName, delimiterForResultName, resultNames)

    def * : SQLSyntax = SQLSyntax(resultNames.map { rn =>
      rn.namedColumns.map { c =>
        s"${aliasName}.${c.value} as ${c.value}${delimiterForResultName}${aliasName}"
      }.mkString(", ")
    }.mkString(", "))

    def column(name: String): SQLSyntax = {
      resultNames.find(rn => rn.namedColumns.exists(_.value.equalsIgnoreCase(name))).map { rn =>
        SQLSyntax(s"${aliasName}.${rn.column(name)} as ${rn.column(name)}${delimiterForResultName}${aliasName}")
      }.getOrElse {
        val registeredNames = resultNames.map { rn => rn.columns.map(_.value).mkString(",") }.mkString(",")
        throw new InvalidColumnNameException(ErrorMessage.INVALID_COLUMN_NAME + s" (name: ${name}, registered names: ${registeredNames})")
      }
    }

  }

  case class SubQueryResultNameSQLSyntaxProvider(
      aliasName: String,
      delimiterForResultName: String,
      resultNames: Seq[BasicResultNameSQLSyntaxProvider[_, _]]) {

    lazy val * : SQLSyntax = SQLSyntax(resultNames.map { rn =>
      rn.namedColumns.map { c =>
        s"${c.value}${delimiterForResultName}${aliasName}"
      }.mkString(", ")
    }.mkString(", "))

    lazy val columns: Seq[SQLSyntax] = resultNames.flatMap { rn =>
      rn.namedColumns.map { c => SQLSyntax(s"${c.value}${delimiterForResultName}${aliasName}") }
    }

    def column(name: String): SQLSyntax = columns.find(_.value.equalsIgnoreCase(name)).getOrElse {
      throw notFoundInColumns(aliasName, name)
    }

    def apply(name: SQLSyntax): SQLSyntax = {
      resultNames.find(rn => rn.namedColumns.exists(_.value.equalsIgnoreCase(name.value))).map { rn =>
        SQLSyntax(s"${rn.namedColumn(name.value).value}${delimiterForResultName}${aliasName}")
      }.getOrElse {
        throw notFoundInColumns(aliasName, name.value)
      }
    }

    def notFoundInColumns(aliasName: String, name: String) = {
      val registeredNames = resultNames.map { rn => rn.namedColumns.map(_.value).mkString(",") }.mkString(",")
      new InvalidColumnNameException(ErrorMessage.INVALID_COLUMN_NAME + s" (name: ${aliasName}.${name}, registered names: ${registeredNames})")
    }

  }

  // -----
  // partial subquery syntax providers

  case class PartialSubQuerySQLSyntaxProvider[S <: SQLSyntaxSupport[A], A](
    aliasName: String,
    override val delimiterForResultName: String,
    underlying: BasicResultNameSQLSyntaxProvider[S, A])
      extends SQLSyntaxProviderCommonImpl[S, A](underlying.support, aliasName)
      with AsteriskProvider {

    val result: PartialSubQueryResultSQLSyntaxProvider[S, A] = {
      PartialSubQueryResultSQLSyntaxProvider(aliasName, delimiterForResultName, underlying)
    }

    val resultName: PartialSubQueryResultNameSQLSyntaxProvider[S, A] = result.name

    lazy val * : SQLSyntax = SQLSyntax(resultName.namedColumns.map { c => s"${aliasName}.${c.value}" }.mkString(", "))

    val asterisk: SQLSyntax = SQLSyntax(aliasName + ".*")

    def apply(name: SQLSyntax): SQLSyntax = {
      underlying.namedColumns.find(_.value.equalsIgnoreCase(name.value)).map { _ =>
        SQLSyntax(s"${aliasName}.${underlying.namedColumn(name.value).value}")
      }.getOrElse {
        throw notFoundInColumns(aliasName, name.value, resultName.columns.map(_.value).mkString(","))
      }
    }

    def column(name: String) = {
      SQLSyntax(s"${aliasName}.${underlying.column(name).value}")
    }

  }

  case class PartialSubQueryResultSQLSyntaxProvider[S <: SQLSyntaxSupport[A], A](
    aliasName: String,
    override val delimiterForResultName: String,
    underlying: BasicResultNameSQLSyntaxProvider[S, A])
      extends SQLSyntaxProviderCommonImpl[S, A](underlying.support, aliasName) {

    val name: PartialSubQueryResultNameSQLSyntaxProvider[S, A] = {
      PartialSubQueryResultNameSQLSyntaxProvider(aliasName, delimiterForResultName, underlying)
    }

    lazy val * : SQLSyntax = SQLSyntax(underlying.namedColumns.map { c =>
      s"${aliasName}.${c.value} as ${c.value}${delimiterForResultName}${aliasName}"
    }.mkString(", "))

    def column(name: String): SQLSyntax = {
      underlying.namedColumns.find(_.value.equalsIgnoreCase(name)).map { nc =>
        SQLSyntax(s"${aliasName}.${nc.value} as ${nc.value}${delimiterForResultName}${aliasName}")
      }.getOrElse {
        throw notFoundInColumns(aliasName, name, underlying.columns.map(_.value).mkString(","))
      }
    }

  }

  case class PartialSubQueryResultNameSQLSyntaxProvider[S <: SQLSyntaxSupport[A], A](
    aliasName: String,
    override val delimiterForResultName: String,
    underlying: BasicResultNameSQLSyntaxProvider[S, A])
      extends SQLSyntaxProviderCommonImpl[S, A](underlying.support, aliasName) with ResultNameSQLSyntaxProvider[S, A] {
    import SQLSyntaxProvider._

    lazy val * : SQLSyntax = SQLSyntax(underlying.namedColumns.map { c =>
      val name = toAliasName(c.value, underlying.support)
      s"${name}${delimiterForResultName}${aliasName}"
    }.mkString(", "))

    override lazy val columns: Seq[SQLSyntax] = underlying.namedColumns.map { c => SQLSyntax(s"${c.value}${delimiterForResultName}${aliasName}") }

    def column(name: String): SQLSyntax = underlying.columns.find(_.value.equalsIgnoreCase(name)).map { original: SQLSyntax =>
      val name = toAliasName(original.value, underlying.support)
      SQLSyntax(s"${name}${delimiterForResultName}${underlying.tableAliasName}${delimiterForResultName}${aliasName}")
    }.getOrElse {
      throw notFoundInColumns(aliasName, name, underlying.columns.map(_.value).mkString(","))
    }

    lazy val namedColumns: Seq[SQLSyntax] = underlying.namedColumns.map { nc: SQLSyntax =>
      SQLSyntax(s"${nc.value}${delimiterForResultName}${aliasName}")
    }

    def namedColumn(name: String) = underlying.namedColumns.find(_.value.equalsIgnoreCase(name)).getOrElse {
      throw notFoundInColumns(aliasName, name, namedColumns.map(_.value).mkString(","))
    }

    def apply(name: SQLSyntax): SQLSyntax = {
      underlying.namedColumns.find(_.value.equalsIgnoreCase(name.value)).map { nc =>
        SQLSyntax(s"${nc.value}${delimiterForResultName}${aliasName}")
      }.getOrElse {
        throw notFoundInColumns(aliasName, name.value, underlying.columns.map(_.value).mkString(","))
      }
    }

  }

  // ---------------------------------
  // Type aliases for this trait elements
  // ---------------------------------

  type ColumnName[A] = ColumnSQLSyntaxProvider[SQLSyntaxSupport[A], A]
  type ResultName[A] = ResultNameSQLSyntaxProvider[SQLSyntaxSupport[A], A]
  type SubQueryResultName = SubQueryResultNameSQLSyntaxProvider
  type SyntaxProvider[A] = QuerySQLSyntaxProvider[SQLSyntaxSupport[A], A]
  type SubQuerySyntaxProvider = SubQuerySQLSyntaxProvider

}


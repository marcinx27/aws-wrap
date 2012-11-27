/*
* Copyright 2012 Pellucid and Zenexity
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package aws.cloudsearch

import java.util.Date

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

import play.api.libs.ws._
import play.api.libs.ws.WS._

import aws.core._
import aws.core.Types._
import aws.core.parsers._
import aws.core.utils._

case class CloudSearchMetadata(requestId: String, time: Duration, cpuTime: Duration) extends Metadata
case class Facet(name: String, constraints: Seq[(String, Int)])

class FacetConstraint private(val field: String, val value: String)
object FacetConstraint {
  def apply(field: String, value: Number) = new FacetConstraint(field, s"$value")
  def apply(field: String, value: String) = new FacetConstraint(field, s"'$value'")
  def apply(field: String, range: Range) = new FacetConstraint(field, s"${range.start}..${range.end}")
  def apply(field: String, from: Option[Int] = None, to: Option[Int] = None) = new FacetConstraint(field, s"""${from.getOrElse("")}..${to.getOrElse("")}""")
  def apply(field: String, values: Seq[String]) = {
    val vs = values.map(v => s"'$v'").mkString(",")
    new FacetConstraint(field, s"$vs")
  }
}


// TODO: would be nice to check for double '-' calls
sealed trait Sort {
  val field: String
  def value: String
}

object Orderings extends Enumeration {
  type Ordering = Value
  val ASC = Value("")
  val DESC = Value("-")
}

object Sort {
  case class Alpha(field: String) extends Sort {
    override val value = "Alpha"
  }
  case class Count(field: String) extends Sort {
    override val value = "Count"
  }
  case class Max(field: String, ordering: Option[Orderings.Ordering] = None) extends Sort {
    override def value = {
      val order = ordering.map(_.toString).getOrElse("")
      s"${order}Max($field)"
    }
    def unary_- = this.copy(ordering = Some(Orderings.DESC))
  }
  case class Sum(field: String) extends Sort {
    override def value = s"Sum($field)"
  }
}

sealed trait Rank {
  val name: String
  val ordering: Option[Orderings.Ordering]
  override def toString = {
    val order = ordering.map(_.toString).getOrElse("")
    s"${order}${name}"
  }
}
object Rank {
  //TODO: DRY
  case class TextRelevance(ordering: Option[Orderings.Ordering] = None) extends Rank{
    val name = "text_relevance"
    def unary_- = this.copy(ordering = Some(Orderings.DESC))
  }
  case class Field(name: String, ordering: Option[Orderings.Ordering] = None) extends Rank {
    def unary_- = this.copy(ordering = Some(Orderings.DESC))
  }
  //TODO: Nicer syntax for expression ?
  // @see: http://docs.amazonwebservices.com/cloudsearch/latest/developerguide/rankexpressions.html
  case class RankExpr(name: String, expr: Option[String] = None, ordering: Option[Orderings.Ordering] = None) extends Rank {
    def unary_- = this.copy(ordering = Some(Orderings.DESC))
  }

  def weight(ws: (String, Float)*) = {
    import play.api.libs.json._
    Json.obj("weights" -> ws.toMap[String, Float]).toString
  }
}

object CloudSearch {

  type WithFacets[T] = (T, Seq[Facet])

  object Parameters {

  }

  private def tryParse[T](resp: Response)(implicit p: Parser[Result[CloudSearchMetadata, T]]) =
    Parser.parse[Result[CloudSearchMetadata, T]](resp).fold(e => throw new RuntimeException(e), identity)

  private def request[T](domain: (String, String), params: Seq[(String, String)] = Nil)(implicit region: CloudSearchRegion, p: Parser[Result[CloudSearchMetadata, T]]) = {

    val allHeaders = Nil
    val version = "2011-02-01"

    WS.url(s"http://search-${domain._1}-${domain._2}.${region.subdomain}.cloudsearch.amazonaws.com/${version}/search")
      .withHeaders(allHeaders: _*)
      .withQueryString(params: _*)
      .get()
      .map(tryParse[T])
  }

  // TODO: open range
  object MatchExpressions {
    sealed trait MatchExpression {
      def and(ex: MatchExpression) = And(this, ex)
      def or(ex: MatchExpression) = Or(this, ex)
    }

    class Field private(name: String, value: String) extends MatchExpression {
      override def toString = s"(field $name $value)"
    }
    object Field {
      def apply(name: String, value: Number) = new Field(name, s"$value")
      def apply(name: String, range: Range) = new Field(name, s"${range.start}..${range.end}")
      def apply(name: String, from: Option[Int] = None, to: Option[Int] = None) = new Field(name, s"""${from.getOrElse("")}..${to.getOrElse("")}""")
      def apply(name: String, value: String) = new Field(name, s"'$value'")
    }

    class Filter private(name: String, value: String) extends MatchExpression {
      override def toString = s"(filter $name $value)"
    }
    object Filter {
      def apply(name: String, value: Number) = new Filter(name, s"$value")
      def apply(name: String, range: Range) = new Filter(name, s"${range.start}..${range.end}")
      def apply(name: String, from: Option[Int] = None, to: Option[Int] = None) = new Filter(name, s"""${from.getOrElse("")}..${to.getOrElse("")}""")
      def apply(name: String, value: String) = new Filter(name, s"'$value'")
    }

    class Not(ex: MatchExpression) extends MatchExpression {
      override def toString = s"(not ${ex})"
    }
    object Not {
      def apply(ex: MatchExpression) = new Not(ex)
    }

    class And(ms: Seq[MatchExpression]) extends MatchExpression {
      override def toString = {
        val es = ms.map(_.toString).mkString(" ")
        s"(and ${es})"
      }
    }
    object And {
      def apply(ms: MatchExpression*) = new And(ms)
    }

    class Or(ms: Seq[MatchExpression]) extends MatchExpression {
      override def toString = {
        val es = ms.map(_.toString).mkString(" ")
        s"(or ${es})"
      }
    }
    object Or {
      def apply(ms: MatchExpression*) = new Or(ms)
    }
  }

  // XXX: Builder ?
  def search[T](
    domain: (String, String),
    query: Option[String] = None,
    matchExpression: Option[MatchExpressions.MatchExpression] = None,
    returnFields: Seq[String] = Nil,
    facets: Seq[String] = Nil,
    facetConstraints: Seq[FacetConstraint] = Nil,
    facetSort: Seq[Sort] = Nil,
    facetTops: Seq[(String, Int)] = Nil,
    ranks: Seq[Rank] = Nil,
    scores: Seq[(String, Range)] = Nil,
    size: Option[Int] = None,
    start: Option[Int] = None)(implicit region: CloudSearchRegion, p: Parser[Result[CloudSearchMetadata, T]]) = {

    val exprs = ranks.flatMap {
      case e: Rank.RankExpr =>
        e.expr.map(x => s"facet-${e.name}-sort" -> x)
      case _ => Nil
    }

    val params =
      query.toSeq.map("q" -> _) ++
      returnFields.reduceLeftOption(_ + "," + _).map("return-fields" -> _).toSeq ++
      matchExpression.map("bq" -> _.toString).toSeq ++
      facets.reduceLeftOption(_ + "," + _).map("facet" -> _).toSeq ++
      size.map("size" -> _.toString).toSeq ++
      start.map("start" -> _.toString).toSeq ++
      facetConstraints.map(c => s"facet-${c.field}-constraints" -> c.value) ++
      facetSort.map(f => s"facet-${f.field}-sort" -> f.value) ++
      facetTops.map(t => s"facet-${t._1}-top-n" -> t._2.toString) ++
      ranks.map(_.toString).reduceLeftOption(_ + "," + _).map("rank" -> _).toSeq ++
      exprs ++
      scores.map(s => s"t-${s._1}" -> s"${s._2.start}..${s._2.end}")

    request[T](domain, params)
  }

}
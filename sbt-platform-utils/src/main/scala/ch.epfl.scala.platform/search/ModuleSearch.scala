package ch.epfl.scala.platform.search

import cats.data.Xor
import ch.epfl.scala.platform.Feedback
import coursier.core.Version
import coursier.core.Version.Literal
import ch.epfl.scala.platform.util.Error
import dispatch.{Req, url}

import scala.language.implicitConversions

trait BintrayApi {
  val baseUrl = "https://bintray.com/api/v1"

  def resolve: Req
}

/** Represent a simple Scala module. */
case class ScalaModule(orgId: String,
                       artifactId: String,
                       scalaBinVersion: String)

/** Represent a resolved module by the Bintray API endpoint. */
case class ResolvedModule(name: String,
                          repo: String,
                          owner: String,
                          desc: Option[String],
                          system_ids: List[String],
                          versions: List[String],
                          latest_version: String)

case class Resolution(info: ScalaModule) extends BintrayApi {
  override def resolve: Req = {
    val postRequest = url(s"$baseUrl/search/packages/maven").GET
    postRequest
      .addHeader("Accept", "application/json")
      .addQueryParameter("g", info.orgId)
      .addQueryParameter("a", s"${info.artifactId}_${info.scalaBinVersion}")
  }
}

/** Search the bintray repositories for all the releases of a concrete
  * module. Rolling our own because coursier does not have support for
  * this, and `latest.release` is not yet implemented. */
object ModuleSearch {

  import io.circe.{Error => _, _}
  import generic.auto._
  import parser._

  type Response[T] = Xor[Error, T]

  implicit class XtensionCirceXor[T](circe: Xor[io.circe.Error, T]) {
    def toResponse: Response[T] =
      circe.leftMap(e => Error(Feedback.parsingError, Some(e)))
  }

  private[platform] def compareAndGetLatest(ms: Seq[ResolvedModule]) = {
    /* The **recommended** way of versioning a nightly is with ALPHA,
     * this filtering is just done if someone uses nightly instead. */
    val nonNightlyVersions = ms
      .map(m => m -> Version(m.latest_version))
      .filterNot(t => t._2.items.contains(Literal("nightly")))
    if (nonNightlyVersions.isEmpty) None
    else Some(nonNightlyVersions.maxBy(_._2)._1)
  }

  def exists(module: ScalaModule, targetVersion: Version): Response[Boolean] = {
    searchInMaven(module).map { results =>
      val bintrayModules = results.filter(rm => rm.repo == "jcenter")
      if (bintrayModules.isEmpty) false
      else {
        val targetModule = bintrayModules.head
        targetModule.versions.contains(targetVersion.repr)
      }
    }
  }

  def searchLatest(module: ScalaModule): Response[Option[ResolvedModule]] =
    searchInMaven(module).map(compareAndGetLatest(_))

  def searchInMaven(module: ScalaModule): Response[List[ResolvedModule]] = {
    import dispatch._
    import dispatch.Defaults._
    import scala.concurrent._
    import scala.concurrent.duration._
    val librarySearchResults = Http(Resolution(module).resolve.OK(
      as.String andThen decode[List[ResolvedModule]] andThen (_.toResponse)))
    Await.result(librarySearchResults, 90.seconds)
  }
}

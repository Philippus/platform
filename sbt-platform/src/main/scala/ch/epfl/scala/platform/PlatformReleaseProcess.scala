package ch.epfl.scala.platform

import ch.epfl.scala.platform
import ch.epfl.scala.platform.search.ModuleSearch
import coursier.core.Version
import org.joda.time.DateTime
import bintray.BintrayPlugin.autoImport._
import cats.data.Xor
import ch.epfl.scala.platform.util.Error
import sbt.{Def, _}
import sbt.complete.Parser
import sbtrelease.ReleaseStateTransformations

import scala.util.Random

object PlatformReleaseProcess extends VersionUtils {

  import PlatformPlugin.autoImport._

  import sbtrelease.Utilities._
  import sbtrelease.ReleasePlugin.autoImport._
  import sbtrelease.ReleaseStateTransformations._
  import sbtrelease.ReleasePlugin.autoImport.ReleaseKeys._

  // Attributes for the custom release command
  val releaseProcessAttr = AttributeKey[String]("releaseProcess")
  val commandLineVersion = AttributeKey[Option[String]]("commandLineVersion")
  val validReleaseVersion = AttributeKey[Version]("validatedReleaseVersions")

  private def generateUbiquituousVersion(version: String, st: State) = {
    val ci = st.extract.get(platformCiEnvironment)
    val unique = ci
      .map(_.build.number.toString)
      .getOrElse(Random.nextLong.abs.toString)
    s"$version-$unique"
  }

  /** Update the SBT tasks and attribute that holds the current version value. */
  def updateCurrentVersion(definedVersion: Version, st: State): State = {
    val updated =
      st.extract.append(Seq(platformCurrentVersion := definedVersion), st)
    updated.put(validReleaseVersion, definedVersion)
  }

  val decideAndValidateVersion: ReleaseStep = { (st: State) =>
    val logger = st.globalLogging.full
    val userVersion = st.get(commandLineVersion).flatten.map(validateVersion)
    val definedVersion =
      userVersion.getOrElse(st.extract.get(platformSbtDefinedVersion))
    // TODO(jvican): Make sure minor and major depend on platform version
    val bumpFunction = st.extract.get(releaseVersionBump)
    val nextVersion =
      bumpFunction.bump.apply(definedVersion.toSbtRelease).toCoursier
    logger.info(s"Current version is $definedVersion.")
    logger.info(s"Next version is set to $nextVersion.")
    updateCurrentVersion(definedVersion, st)
      .put(versions, (definedVersion.repr, nextVersion.repr))
  }

  val checkVersionIsNotPublished: ReleaseStep = { (st: State) =>
    val definedVersion = st
      .get(validReleaseVersion)
      .getOrElse(sys.error(Feedback.undefinedVersion))
    val module = st.extract.get(platformScalaModule)
    // TODO(jvican): Improve error handling here
    ModuleSearch
      .exists(module, definedVersion)
      .flatMap { exists =>
        if (!exists) Xor.right(st)
        else {
          val msg = Feedback.versionIsAlreadyPublished(definedVersion.toString)
          Xor.left(Error(msg))
        }
      }
      .fold(e => sys.error(e.msg), identity)
  }

  object PlatformParseResult {
    case class ReleaseProcess(value: String) extends ParseResult
  }

  import sbt.complete.DefaultParsers.{Space, token, StringBasic}

  val releaseProcessToken = "release-process"
  val ReleaseProcess: Parser[ParseResult] =
    (Space ~> token("release-process") ~> Space ~> token(
      StringBasic,
      "<nightly | stable>")) map PlatformParseResult.ReleaseProcess

  val releaseParser: Parser[Seq[ParseResult]] = {
    (ReleaseProcess ~ (ReleaseVersion | SkipTests | CrossBuild).*).map {
      args =>
        val (mandatoryArg, optionalArgs) = args
        mandatoryArg +: optionalArgs
    }
  }

  def setAndReturnReleaseParts(releaseProcess: TaskKey[Seq[ReleaseStep]],
                               st: State): (State, Seq[ReleaseStep]) = {
    val extracted = Project.extract(st)
    val (st1, parts) = extracted.runTask(releaseProcess, st)
    // Set the active release process before returning the release parts
    val active = platformActiveReleaseProcess := Some(parts)
    val st2 = extracted.append(active, st1)
    (st2, parts)
  }

  private def setBintrayRepository(repo: String, st: State): State = {
    val extracted = Project.extract(st)
    extracted.append(bintrayRepository := repo, st)
  }

  val FailureCommand = "--failure--"
  val releaseCommand: Command =
    Command("releaseModule")(_ => releaseParser) { (st, args) =>
      val logger = st.globalLogging.full
      val extracted = Project.extract(st)
      val crossEnabled = extracted.get(releaseCrossBuild) ||
        args.contains(ParseResult.CrossBuild)
      val selectedReleaseProcess = args
        .collectFirst {
          case PlatformParseResult.ReleaseProcess(value) => value
        }
        .getOrElse(Feedback.missingReleaseProcess)

      val startState = st
        .copy(onFailure = Some(FailureCommand))
        .put(releaseProcessAttr, selectedReleaseProcess)
        .put(skipTests, args.contains(ParseResult.SkipTests))
        .put(cross, crossEnabled)
        .put(commandLineVersion, args.collectFirst {
          case ParseResult.ReleaseVersion(value) => value
        })

      val (updatedState, releaseParts) = {
        selectedReleaseProcess.toLowerCase match {
          case "on-merge" =>
            logger.info("On-merge release process has been selected.")
            val withRepo =
              setBintrayRepository(PlatformNightliesRepo, startState)
            setAndReturnReleaseParts(platformOnMergeReleaseProcess,
                                     withRepo)
          case "nightly" =>
            logger.info("Nightly release process has been selected.")
            val withNightlies =
              setBintrayRepository(PlatformNightliesRepo, startState)
            setAndReturnReleaseParts(platformNightlyReleaseProcess,
                                     withNightlies)
          case "stable" =>
            logger.info("Stable release process has been selected.")
            val withReleases =
              setBintrayRepository(PlatformReleasesRepo, startState)
            setAndReturnReleaseParts(platformStableReleaseProcess,
                                     withReleases)
          case rp => sys.error(Feedback.unexpectedReleaseProcess(rp))
        }
      }

      val initialChecks = releaseParts.map(_.check)
      val process = releaseParts.map { step =>
        if (step.enableCrossBuild && crossEnabled) {
          filterFailure(ReleaseStateTransformations.runCrossBuild(step.action)) _
        } else filterFailure(step.action) _
      }

      val removeFailureCommand = { s: State =>
        s.remainingCommands match {
          case FailureCommand :: tail => s.copy(remainingCommands = tail)
          case _ => s
        }
      }
      initialChecks.foreach(_(updatedState))
      Function.chain(process :+ removeFailureCommand)(updatedState)
    }

  object OnMerge {
    val Alias: String = "releaseModule release-process on-merge"

    val tagOnMerge: ReleaseStep = { (st0: State) =>
      val extracted = st0.extract
      val (st, logger) = extracted.runTask(platformLogger, st0)
      val targetVersion = st
        .get(validReleaseVersion)
        .getOrElse(sys.error(Feedback.validVersionNotFound))
      val droneEnv = st.extract.get(platformCiEnvironment)

      val mergeVersion: Version = {
        val maybeVersion = droneEnv.flatMap { env =>
          env.commit.sha match {
            case "" => None
            case sha => Some(Version(s"${targetVersion.repr}-$sha"))
          }
        }
        maybeVersion.getOrElse(sys.error(Feedback.undefinedCommitHash))
      }

      logger.info(s"On-merge version is set to $mergeVersion")
      val previousVersions =
        st.get(versions).getOrElse(sys.error(Feedback.undefinedVersion))
      updateCurrentVersion(mergeVersion, st)
        .put(versions, (mergeVersion.repr, previousVersions._2))
    }

    val releaseProcess: Seq[ReleaseStep] = {
      Seq[ReleaseStep](
        decideAndValidateVersion,
        tagOnMerge,
        checkVersionIsNotPublished,
        setReleaseVersion,
        releaseStepTask(platformValidatePomData),
        checkSnapshotDependencies,
        runTest,
        releaseStepTask(platformRunMiMa),
        releaseStepTask(platformBeforePublishHook),
        publishArtifacts,
        releaseStepTask(platformAfterPublishHook),
        releaseStepTask(bintrayRelease)
      )
    }
  }

  object Nightly {
    val Alias = "releaseModule release-process nightly"

    val tagAsNightly: ReleaseStep = { (st0: State) =>
      val (st, logger) = st0.extract.runTask(platformLogger, st0)
      val targetVersion = st
        .get(validReleaseVersion)
        .getOrElse(sys.error(Feedback.validVersionNotFound))
      val now = DateTime.now()
      val month = now.dayOfMonth().get
      val day = now.monthOfYear().get
      val year = now.year().get
      val template = s"${targetVersion.repr}-alpha-$year-$month-$day"
      val nightlyVersion =
        if (!platform.testing) template
        else generateUbiquituousVersion(template, st)
      val generatedVersion = targetVersion.copy(nightlyVersion)
      logger.info(s"Nightly version is set to ${generatedVersion.repr}.")
      val previousVersions =
        st.get(versions).getOrElse(sys.error(Feedback.undefinedVersion))
      updateCurrentVersion(generatedVersion, st)
        .put(versions, (generatedVersion.repr, previousVersions._2))
    }

    val releaseProcess: Seq[ReleaseStep] = {
      Seq[ReleaseStep](
        decideAndValidateVersion,
        tagAsNightly,
        checkVersionIsNotPublished,
        setReleaseVersion,
        releaseStepTask(platformValidatePomData),
        checkSnapshotDependencies,
        runTest,
        releaseStepTask(platformRunMiMa),
        releaseStepTask(platformBeforePublishHook),
        publishArtifacts,
        releaseStepTask(platformAfterPublishHook),
        releaseStepTask(bintrayRelease)
      )
    }
  }

  object Stable {
    val Alias = "releaseModule release-process stable"

    def cleanUpTag(tag: String): String =
      if (tag.startsWith("v")) tag.replaceFirst("v", "") else tag

    val setVersionFromGitTag: ReleaseStep = { (st: State) =>
      val logger = st.globalLogging.full
      val commandLineDefinedVersion = st.get(commandLineVersion)
      // Command line version always takes precedence
      val specifiedVersion = commandLineDefinedVersion.flatten match {
        case Some(version) if version.nonEmpty => version
        case None =>
          val ciInfo = st.extract.get(platformCiEnvironment)
          ciInfo.map(e => e.tag) match {
            case Some(Some(versionTag)) => versionTag
            case Some(None) => sys.error(Feedback.expectedGitTag)
            case None => sys.error(Feedback.onlyCiCommand("releaseStable"))
          }
      }

      // TODO(jvican): Separate testing from main logic
      val stableVersion = if (platform.testing) {
        generateUbiquituousVersion(cleanUpTag(specifiedVersion), st)
      } else cleanUpTag(specifiedVersion)
      logger.info(s"Version read from the git tag: $stableVersion")
      st.put(commandLineVersion, Some(stableVersion))
    }

    val releaseProcess: Seq[ReleaseStep] = {
      Seq[ReleaseStep](
        setVersionFromGitTag,
        decideAndValidateVersion,
        checkVersionIsNotPublished,
        setReleaseVersion,
        releaseStepTask(platformValidatePomData),
        checkSnapshotDependencies,
        runTest,
        releaseStepTask(platformRunMiMa),
        releaseStepTask(platformBeforePublishHook),
        publishArtifacts,
        releaseStepTask(platformReleaseToGitHub),
        releaseStepTask(platformAfterPublishHook),
        releaseStepTask(bintrayRelease)
      )
    }

  }

}

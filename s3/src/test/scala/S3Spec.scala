package aws.s3

import scala.concurrent.Future
import play.api.libs.ws._
import play.api.libs.ws.WS._

import aws.core._
import aws.s3.models._
import aws.s3.Permissions._
import org.specs2.mutable._

import scala.concurrent._
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit._

import scala.concurrent.ExecutionContext.Implicits.global

object TestUtils extends Specification { // Evil hack to access Failure
  implicit val region = S3Region.EU_WEST_1

  def checkResult[M <: Metadata, T](r: Result[M, T]) = r match {
    case AWSError(_, _, message) => failure(message)
    case Result(_, _) => success
  }

  def waitFor[T](f: Future[T]) = Await.result(f, Duration(30, SECONDS))

  def del(name: String) = {
    val delete = waitFor(S3.Bucket.delete(name))
    checkResult(delete)
  }
}

import TestUtils._

object BucketSpec extends Specification {

  "S3 Bucket API" should {

    "Create and delete a bucket" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketCreate"
      val create = waitFor(S3.Bucket.create(bucketName))
      checkResult(create)
      del(bucketName)
    }

    "Create a private bucket using canned ACL" in {
      import aws.s3.ACLs._
      val bucketName = aws.s3.AWS.awsKey + "testBucketAcl"
      val res = waitFor(S3.Bucket.create(bucketName, Some(PRIVATE)))
      checkResult(res)
      del(bucketName)
    }

    "Create a private bucket with explicit permisions" in {
      import Grantees._
      val bucketName = aws.s3.AWS.awsKey + "testBucketPerms"
      val perms =
        GRANT_READ(Email("erwan.loisant@pellucid.com"), Email("dustin.whitney@pellucid.com")) ::
        GRANT_WRITE(Email("erwan.loisant@pellucid.com")) ::
        GRANT_READ_ACP(Email("erwan.loisant@pellucid.com")) :: Nil

      val res = waitFor(S3.Bucket.create(bucketName, permissions = perms))
      checkResult(res)
      del(bucketName)
    }

    "List buckets" in {
      val res = waitFor(S3.Bucket.list())
      checkResult(res)
    }

    "Enable versionning" in {
      import play.api.libs.iteratee._
      import aws.s3.S3._

      val bucketName = aws.s3.AWS.awsKey + "testBucketEnableVersion"
      val cr = waitFor(S3.Bucket.create(bucketName))

      val res = waitFor(S3.Bucket.setVersioningConfiguration(bucketName, VersionStates.ENABLED))

      checkResult(res)
      del(bucketName)
    }

    "Enable versionning and MFA" in {
      skipped("Needs an MFA device")
      import play.api.libs.iteratee._
      import aws.s3.S3._

      val bucketName = aws.s3.AWS.awsKey + "testBucketEnableMFA"
      val cr = waitFor(S3.Bucket.create(bucketName))

      val res = waitFor(S3.Bucket.setVersioningConfiguration(bucketName, VersionStates.ENABLED, Some(MFADeleteStates.ENABLED -> MFA(???, ???))))

      checkResult(res)
      del(bucketName)
    }
  }
}

object LoggingSpec extends Specification {
  "S3 Bucket Logging API" should {

    "Enable Logging" in {
      import Grantees._
      val ps =
        GRANT_WRITE(Uri("http://acs.amazonaws.com/groups/s3/LogDelivery")) ::
        GRANT_READ_ACP(Uri("http://acs.amazonaws.com/groups/s3/LogDelivery")) :: Nil

      val target = aws.s3.AWS.awsKey + "testBucketLoggingTarget"
      val t = waitFor(S3.Bucket.create(target, permissions = ps))

      val logged = aws.s3.AWS.awsKey + "testBucketLogging"
      waitFor(S3.Bucket.create(logged))

      val res = waitFor(Logging.enable(logged, target, Seq(Email("dustin.whitney@pellucid.com") -> Logging.LoggingPermisions.FULL_CONTROL)))

      del(logged)
      del(target)

      checkResult(res)
    }

    "Show Logging Statuses" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketLoggingStatuses"
      waitFor(S3.Bucket.create(bucketName))
      val res = waitFor(Logging.get(bucketName))
      checkResult(res)
      del(bucketName)
    }

  }
}

object TagSpec extends Specification {
  "S3 Bucket Tagging API" should {
    "create tags" in {
      val tagged = aws.s3.AWS.awsKey + "testBucketLoggingTagged"
      val c = waitFor(S3.Bucket.create(tagged))
      val res = waitFor(Tag.create(tagged, Tag("Project", "Project One"), Tag("User", "jsmith")))
      del(tagged)
      checkResult(res)
    }

    "list tags" in {
      val tagged = aws.s3.AWS.awsKey + "testBucketLoggingTaggedList"
      val c = waitFor(S3.Bucket.create(tagged))
      val tags = Seq(Tag("Project", "Project One"), Tag("User", "jsmith"))
      waitFor(Tag.create(tagged, tags: _*))

      val res = waitFor(Tag.get(tagged))

      del(tagged)

      checkResult(res)
      res.body must haveSize(2)
      res.body must containAllOf(tags)
    }

    "delete tags" in {
      val tagged = aws.s3.AWS.awsKey + "testBucketLoggingTaggedDelete"
      val c = waitFor(S3.Bucket.create(tagged))
      val tags = Seq(Tag("Project", "Project One"), Tag("User", "jsmith"))
      waitFor(Tag.create(tagged, tags: _*))
      val res = waitFor(Tag.delete(tagged))
      // get returns "AWSError(NoSuchTagSet,The TagSet does not exist)"
      //val res = Await.result(Tag.get(tagged), Duration(30, SECONDS))
      del(tagged)
      checkResult(res)
    }
  }
}

object CORSSpec extends Specification {

  import S3.HTTPMethods._

  "S3 Bucket CORS API" should {
    "create cors" in {
      val cors = aws.s3.AWS.awsKey + "testBucketCors"
      val cr = waitFor(S3.Bucket.create(cors))

      val rules = Seq(
        CORSRule(
          origins = Seq("http://*.zenexity.com"),
          methods = PUT :: POST :: GET :: Nil))

      val res = waitFor(CORSRule.create(cors, rules: _*))

      del(cors)
      checkResult(res)
    }

    "get cors" in {
      val cors = aws.s3.AWS.awsKey + "testBucketCorsGet"
      val cr = waitFor(S3.Bucket.create(cors))

      val rules = Seq(
        CORSRule(
          origins = Seq("http://*.zenexity.com"),
          methods = PUT :: POST :: GET :: Nil))

      waitFor(CORSRule.create(cors, rules: _*))

      var res = waitFor(CORSRule.get(cors))
      del(cors)
      checkResult(res)

      res.body must haveSize(1)
      res.body must containAllOf(rules)
    }

    "delete cors" in {
      val cors = aws.s3.AWS.awsKey + "testBucketCorsDelete"
      val cr = waitFor(S3.Bucket.create(cors))

      val rules = Seq(
        CORSRule(
          origins = Seq("http://*.zenexity.com"),
          methods = PUT :: POST :: GET :: Nil))

      waitFor(CORSRule.create(cors, rules: _*))
      waitFor(CORSRule.get(cors))
      val res = waitFor(CORSRule.delete(cors))

      del(cors)
      checkResult(res)
    }

  }
}

object LifecycleSpec extends Specification {

  "S3 Bucket lifecycle API" should {
    "create lifecycle" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketLifecycle"

      val cr = waitFor(S3.Bucket.create(bucketName))
      val res = waitFor(LifecycleConf.create(bucketName, LifecycleConf(Some("42"), "test-", LifecycleConf.Statuses.ENABLED, Duration(42, DAYS))))

      del(bucketName)
      checkResult(res)
    }

    "delete lifecycle" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketLifecycleDelete"

      val cr = waitFor(S3.Bucket.create(bucketName))
      waitFor(LifecycleConf.create(bucketName, LifecycleConf(Some("42"), "test-", LifecycleConf.Statuses.ENABLED, Duration(42, DAYS))))
      val res = waitFor(LifecycleConf.delete(bucketName))

      del(bucketName)
      checkResult(res)
    }

    "get lifecycle" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketLifecycleGet"

      val cr = waitFor(S3.Bucket.create(bucketName))
      val conf = LifecycleConf(Some("42"), "test-", LifecycleConf.Statuses.ENABLED, Duration(42, DAYS))
      waitFor(LifecycleConf.create(bucketName, conf))
      val res = waitFor(LifecycleConf.get(bucketName))

      del(bucketName)
      checkResult(res)
      res.body must_== Seq(conf)
    }
  }
}

object S3ObjectSpec extends Specification {

  "S3 Object API" should {

    "get versions" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketVersions"
      val cr = waitFor(S3.Bucket.create(bucketName))

      // TODO: put objects to get versions
      val res = waitFor(S3Object.getVersions(bucketName))

      del(bucketName)
      checkResult(res)
    }

    "get contents" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketContent"
      val cr = waitFor(S3.Bucket.create(bucketName))

      // TODO: put objects to get versions
      val res = waitFor(S3Object.content(bucketName))
      del(bucketName)
      checkResult(res)
    }

    "upload & delete objects" in {
      import play.api.libs.iteratee._

      val bucketName = aws.s3.AWS.awsKey + "testBucketUp"
      val cr = waitFor(S3.Bucket.create(bucketName))

      val f = new java.io.File("s3/src/test/resources/fry.gif")
      if(!f.exists)
        skipped(s"File not found: $f")

      val resUp = waitFor(S3Object.put(bucketName, f))
      val resDel = waitFor(S3Object.delete(bucketName, f.getName))
      del(bucketName)
      checkResult(resUp)
      checkResult(resDel)
    }

    "delete a specific version" in {
      import play.api.libs.iteratee._
      import aws.s3.S3._

      val bucketName = aws.s3.AWS.awsKey + "testBucketDelVersions"
      val cr = waitFor(S3.Bucket.create(bucketName))

      val vr = waitFor(Bucket.setVersioningConfiguration(bucketName, VersionStates.ENABLED))

      val f = new java.io.File("s3/src/test/resources/fry.gif")
      if(!f.exists)
        skipped(s"File not found: $f")

      val resUp = waitFor(S3Object.put(bucketName, f))
      var version = resUp.metadata.versionId

      val resDel = waitFor(S3Object.delete(bucketName, f.getName, version))

      // cleanup
      val versions = waitFor(S3Object.getVersions(bucketName))
      val ids = for(v <- versions.body.versions;
        id <- v.id
      ) yield f.getName -> Some(id)
      waitFor(S3Object.batchDelete(bucketName, ids))

      del(bucketName)
      checkResult(resDel)
      resDel.metadata.versionId must_== resUp.metadata.versionId
    }

    "batch delete objects" in {
      import play.api.libs.iteratee._
      import scala.language.postfixOps

      val bucketName = aws.s3.AWS.awsKey + "testBucketDel"
      val cr = waitFor(S3.Bucket.create(bucketName))

      val f = new java.io.File("s3/src/test/resources/fry.gif")
      val f2 = new java.io.File("s3/src/test/resources/fry2.jpg")

      if(!f.exists)
        skipped(s"File not found: $f")
      if(!f2.exists)
        skipped(s"File not found: $f2")

      val resUp1 = waitFor(S3Object.put(bucketName, f))
      val resUp2 = waitFor(S3Object.put(bucketName, f2))

      val resDel = waitFor(S3Object.batchDelete(bucketName, Seq(f.getName -> None, f2.getName -> None)))

      val content = waitFor(S3Object.content(bucketName))


      del(bucketName)

      checkResult(resUp1)
      checkResult(resUp2)
      checkResult(resDel)

      content.body.contents must be empty
    }

  }
}

object PolicySpec extends Specification {

  "S3 Policy API" should {

    import Policy.Conditions
    import Conditions.Keys._

    def policy(bucketName: String) = Policy(
      id = Some("aaaa-bbbb-cccc-dddd"),
      statements = Seq(
        Statement(
          effect = Policy.Effects.ALLOW,
          sid = Some("1"),
          principal = Some("AWS" -> Seq("*")),
          action = Seq("s3:GetObject*"),
          conditions = Seq(
            Conditions.Strings.Equals(USER_AGENT -> Seq("PAF")),
            Conditions.Exists(KeyFor(REFERER) -> Seq(true))),
          resource = Seq("arn:aws:s3:::%s/*".format(bucketName.toLowerCase)))))

    "create policy" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketPoliciesCreate"
      val cr = waitFor(S3.Bucket.create(bucketName))
      val res = waitFor(Policy.create(bucketName, policy(bucketName)))

      del(bucketName)
      checkResult(res)
    }

    "get policy" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketPoliciesGet"
      val cr = waitFor(S3.Bucket.create(bucketName))
      waitFor(Policy.create(bucketName, policy(bucketName)))

      val res = waitFor(Policy.get(bucketName))

      del(bucketName)
      checkResult(res)

      // XXX: toString == Evil workaround
      res.body.toString must_== policy(bucketName).toString
    }

    "delete policy" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketPoliciesDelete"
      val cr = waitFor(S3.Bucket.create(bucketName))
      waitFor(Policy.create(bucketName, policy(bucketName)))
      val res = waitFor(Policy.delete(bucketName))
      del(bucketName)
      checkResult(res)
    }

  }
}


object NotificationsSpec extends Specification {
  "S3 Notification API" should {

    "create notification conf" in {
      skipped("Needs a topic to be created")
      val bucketName = aws.s3.AWS.awsKey + "testBucketNotifCreate"
      val cr = waitFor(S3.Bucket.create(bucketName))
      val res = waitFor(NotificationConfiguration.create(bucketName, NotificationConfiguration(s"arn:aws:sns:${region.subdomain}:123456789012:myTopic", Events.REDUCED_REDUNDANCY_LOST_OBJECT)))

      del(bucketName)
      checkResult(res)
    }

    "get notification conf" in {
      val bucketName = aws.s3.AWS.awsKey + "testBucketNotifGet"
      val cr = waitFor(S3.Bucket.create(bucketName))

      val res = waitFor(NotificationConfiguration.get(bucketName))

      del(bucketName)
      checkResult(res)
    }
  }
}
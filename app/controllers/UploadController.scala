package controllers

/**
 * @author kal
 */
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.json._
import org.apache.commons.codec.binary.Base64
import java.util.UUID
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3._
import com.amazonaws.auth._
import play.api.mvc.MultipartFormData._
import play.api.mvc.BodyParsers.parse._
import play.api.libs.iteratee._

trait UploadController extends Controller with Session {
  val s3Client: AmazonS3Client
  val badRequest = BadRequest("Invalid Request")
  val futBadRequest = Future.successful(badRequest)
  val futUnauthorized = Future.successful(Unauthorized)
  val futForbidden = Future.successful(Forbidden)

  def Authenticated[A](fileId: String)(action: Action[A]): Action[A] = {
    def requestToken[T <: RequestHeader](r: T): Option[String] = r.queryString.get("access_token").flatMap(_.headOption)
    def f[A](a: A): A = a
    
    def authenticationChecker[A <: RequestHeader , T]( r: A)(b: T , c:Future[Result] => T ): T = {
      requestToken(r) map { token =>
        getUserForAccessToken(token) map { u =>
        if (u == "login") {
          if (fileId == "foo") b else c(futForbidden)
        } else {
            c(futUnauthorized) // Sneaky! Trying to use someone else's access Token
          }
        } getOrElse {
          c(futUnauthorized) // This is an invalid token 
        }
      } getOrElse c(futBadRequest)
    }
    
    val authenticatedBodyParser = parse.using { r =>
      authenticationChecker(r)(action.parser, parse.error)
    }
    
    Action.async(authenticatedBodyParser) { request =>
      action(request)
    }
  }

  def uploadFile(fileId: String) = Authenticated(fileId) {
    Action.async(s3Parser(fileId)) { r =>
      val FilePart(_, _, _, keyName) = r.body.files.head
      if (keyName == "error") {
        Future.successful(InternalServerError("Could not save file to S3"))
      } else {
        Future.successful(Ok(keyName))
        // Use this section to record successful S3 upload to a database or some such.
      }
    }
  }

  def s3Parser(fileId: String) = {
    val fiveMBConsumer = Traversable.takeUpTo[Array[Byte]](5 * 1024 * 1024) &>> Iteratee.consume()
    val rechunker = Enumeratee.grouped(fiveMBConsumer)
    val bucket = "upload-test-file" // Make sure you have a bucket with this name.
    multipartFormData(Multipart.handleFilePart({
      case Multipart.FileInfo(_, _, _) => {
        val keyName = fileId
        var position = 0
        val etags = new java.util.ArrayList[PartETag]()

        val req = new InitiateMultipartUploadRequest(bucket, keyName)
        val res = s3Client.initiateMultipartUpload(req)

        (rechunker &>> Iteratee.foldM[Array[Byte], Int](1) { (c, bytes) =>
          Future {
            println("Received chunk")
            val is = new java.io.ByteArrayInputStream(bytes)
            val uploadRequest = new UploadPartRequest()
              .withBucketName(bucket).withKey(keyName)
              .withUploadId(res.getUploadId())
              .withPartNumber(c)
              .withFileOffset(position)
              .withInputStream(is)
              .withPartSize(bytes.length)
            etags.add(s3Client.uploadPart(uploadRequest).getPartETag)
            position = position + bytes.length
            c + 1
          }
        }).map { v =>
          try {
            val comp = new CompleteMultipartUploadRequest(bucket, keyName, res.getUploadId(), etags)
            s3Client.completeMultipartUpload(comp)
            s3Client.setObjectAcl(bucket, keyName, com.amazonaws.services.s3.model.CannedAccessControlList.AuthenticatedRead)
            keyName
          } catch {
            case e: Exception => {
              println("S3 upload Ex " + e.getMessage())
              val abort = new AbortMultipartUploadRequest("abortBucket", "foo_bar", res.getUploadId())
              s3Client.abortMultipartUpload(abort);
              "error"
            }
          }
        }
      }
    }))
  }
}

object UploadController extends UploadController {
  override val s3Client = new AmazonS3Client
}
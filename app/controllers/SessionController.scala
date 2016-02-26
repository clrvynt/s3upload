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

trait SessionController extends Controller with Session {
  def login = Action.async { req =>
    req.headers.get("Authorization").map { auth =>
      val userpass = decodeBase64(auth.split(" ").tail.head).split(":")
      val (user, pass) = (userpass.headOption, userpass.drop(1).headOption.getOrElse(""))
      
      user.map { us =>
        if (us == "login" && pass == "password") {
          val uuid = UUID.randomUUID.toString
          saveAccessTokenForUser(uuid, us)
          Future.successful(Ok(uuid))
        }
        else {
          futureUnauthorized
        }
      } getOrElse futureUnauthorized
    } getOrElse futureUnauthorized
  }
  private val futureUnauthorized = Future.successful(Unauthorized)
  private def decodeBase64(str: String) = new String(Base64.decodeBase64(str), "UTF-8")
}

trait Session {
  private var sessionMap = new java.util.concurrent.ConcurrentHashMap[String, String]
  def saveAccessTokenForUser(token: String, user: String) = sessionMap.put(token, user)
  def getUserForAccessToken(token: String) = {
    val r = sessionMap.get(token)
    if ( r == null) 
      None
    else 
      Some(r)
  }
}

object SessionController extends SessionController
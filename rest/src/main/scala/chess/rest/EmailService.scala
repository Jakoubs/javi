package chess.rest

import cats.effect.IO
import jakarta.mail.*
import jakarta.mail.internet.*
import java.util.Properties

/**
 * Service for sending emails via SMTP (e.g., Gmail).
 */
class EmailService(
  val host: String,
  val port: String,
  val user: String,
  val pass: String,
  val from: String
):
  def sendVerificationEmail(to: String, username: String, token: String): IO[Either[String, String]] = {
    val verificationLink = s"http://localhost:8080/api/auth/verify?token=$token"
    
    val htmlContent = s"""<!doctype html>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head>
    <title>Willkommen bei Javi Chess</title>
    <style type="text/css">
      body { font-family: Ubuntu, Helvetica, Arial, sans-serif; background-color: #f4f4f4; margin: 0; padding: 20px; }
      .container { max-width: 600px; margin: 0 auto; background: white; padding: 40px; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }
      .header { text-align: center; color: #169179; font-size: 32px; font-weight: bold; margin-bottom: 30px; }
      .content { font-size: 18px; line-height: 1.6; color: #333; }
      .button-container { text-align: center; margin-top: 30px; }
      .button { background-color: #508C7C; color: white; padding: 15px 25px; text-decoration: none; border-radius: 5px; font-weight: bold; display: inline-block; }
      .footer { text-align: center; margin-top: 40px; }
      img { max-width: 100%; border-radius: 8px; }
    </style>
  </head>
  <body>
    <div class="container">
      <div class="header">JAVI Chess</div>
      <div class="content">
        <p>Hallo $username,</p>
        <p>willkommen bei Javi! Wir freuen uns riesig, dich in unserer Community begrüßen zu dürfen. Das Brett ist aufgebaut und deine Figuren stehen bereit – jetzt fehlt nur noch ein kleiner Schritt, um zu starten.</p>
        <p>Bitte bestätige deine E-Mail-Adresse, damit wir deinen Account aktivieren können: </p>
      </div>
      <div class="button-container">
        <a href="$verificationLink" class="button">Account aktivieren</a>
      </div>
      <div class="footer">
        <img src="https://media2.giphy.com/media/v1.Y2lkPWI2Yjc3N2Y0Zno3MnUwNnc2ZDhtc2w1c3BxMndmbWM4Zno5dmRxcnJvYTZ4cmNpNiZlcD12MV9naWZzX3NlYXJjaCZjdD1n/32dfpYx8kBX1bXSEu8/giphy.gif" alt="Chess Celebration">
      </div>
    </div>
  </body>
</html>"""

    sendEmail(to, "Javi Chess - Willkommen an Bord!", htmlContent)
  }

  private def sendEmail(to: String, subject: String, html: String): IO[Either[String, String]] = IO.blocking {
    val props = new Properties()
    props.put("mail.smtp.auth", "true")
    props.put("mail.smtp.starttls.enable", "true")
    props.put("mail.smtp.host", host)
    props.put("mail.smtp.port", port)
    props.put("mail.smtp.ssl.trust", host)
    props.put("mail.smtp.starttls.required", "true")
    props.put("mail.debug", "true")

    val session = Session.getInstance(props, new Authenticator() {
      override protected def getPasswordAuthentication: PasswordAuthentication = {
        new PasswordAuthentication(user, pass)
      }
    })

    try {
      val message = new MimeMessage(session)
      message.setFrom(new InternetAddress(from))
      message.setRecipients(Message.RecipientType.TO, to)
      message.setSubject(subject)
      
      // Set content directly as HTML instead of using MimeMultipart
      // This avoids "no object DCH for MIME type multipart/mixed" errors
      message.setContent(html, "text/html; charset=utf-8")

      Transport.send(message)
      println(s"[EMAIL] Email sent successfully to $to via $host")
      Right("Success")
    } catch {
      case e: Exception =>
        println(s"[ERROR] SMTP Error: ${e.getMessage}")
        e.printStackTrace()
        Left(s"SMTP Error: ${e.getMessage}")
    }
  }.handleErrorWith { e =>
    val msg = s"Internal Error: ${e.getMessage}"
    IO.println(s"[ERROR] $msg") *> 
    IO(e.printStackTrace()) *>
    IO.pure(Left(msg))
  }

object EmailService:
  def fromEnv(): IO[EmailService] = IO {
    val host = sys.env.getOrElse("SMTP_HOST", "smtp.gmail.com").trim
    val port = sys.env.getOrElse("SMTP_PORT", "587").trim
    val user = sys.env.getOrElse("SMTP_USER", "").trim
    val pass = sys.env.getOrElse("SMTP_PASS", "").trim
    val from = sys.env.getOrElse("EMAIL_FROM", user).trim

    if (user.isEmpty || pass.isEmpty) {
      println("[WARN] SMTP credentials missing. Emails will not be sent.")
    } else {
      println(s"[DEBUG] EmailService initialized for $user via $host:$port")
    }
    
    new EmailService(host, port, user, pass, from)
  }

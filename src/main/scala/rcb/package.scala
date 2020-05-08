import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.bind.DatatypeConverter

package object rcb {
  def getBitmexApiSignature(keyString: String, apiSecret: String): String = {
    val sha256HMAC = Mac.getInstance("HmacSHA256")
    val secretKey = new SecretKeySpec(apiSecret.getBytes("UTF-8"), "HmacSHA256")
    sha256HMAC.init(secretKey)
    val hash = DatatypeConverter.printHexBinary(sha256HMAC.doFinal(keyString.getBytes))
    hash
  }

  object OrderSide extends Enumeration {
    type OrderSide = Value
    val Buy, Sell = Value
  }

  object OrderAction extends Enumeration {
    type OrderAction = Value
    val Place, Ammend, Cancel = Value
  }
}

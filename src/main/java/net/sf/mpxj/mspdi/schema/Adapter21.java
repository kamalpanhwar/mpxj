//
// This file was generated by the Eclipse Implementation of JAXB, v2.3.3
// See https://eclipse-ee4j.github.io/jaxb-ri
// Any modifications to this file will be lost upon recompilation of the source schema.
// Generated on: 2022.05.03 at 03:06:19 PM BST
//

package net.sf.mpxj.mspdi.schema;

import java.util.UUID;
import javax.xml.bind.annotation.adapters.XmlAdapter;

@SuppressWarnings("all") public class Adapter21 extends XmlAdapter<String, UUID>
{

   public UUID unmarshal(String value)
   {
      return (net.sf.mpxj.mspdi.DatatypeConverter.parseUUID(value));
   }

   public String marshal(UUID value)
   {
      return (net.sf.mpxj.mspdi.DatatypeConverter.printUUID(value));
   }

}

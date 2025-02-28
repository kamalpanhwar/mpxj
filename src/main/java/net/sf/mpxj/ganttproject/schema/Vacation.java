//
// This file was generated by the Eclipse Implementation of JAXB, v2.3.3
// See https://eclipse-ee4j.github.io/jaxb-ri
// Any modifications to this file will be lost upon recompilation of the source schema.
// Generated on: 2021.04.09 at 11:15:26 AM BST
//

package net.sf.mpxj.ganttproject.schema;

import java.util.Date;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlSchemaType;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * &lt;p&gt;Java class for vacation complex type.
 *
 * &lt;p&gt;The following schema fragment specifies the expected content contained within this class.
 *
 * &lt;pre&gt;
 * &amp;lt;complexType name="vacation"&amp;gt;
 *   &amp;lt;simpleContent&amp;gt;
 *     &amp;lt;extension base="&amp;lt;http://www.w3.org/2001/XMLSchema&amp;gt;string"&amp;gt;
 *       &amp;lt;attribute name="start" type="{http://www.w3.org/2001/XMLSchema}date" /&amp;gt;
 *       &amp;lt;attribute name="end" type="{http://www.w3.org/2001/XMLSchema}date" /&amp;gt;
 *       &amp;lt;attribute name="resourceid" type="{http://www.w3.org/2001/XMLSchema}int" /&amp;gt;
 *     &amp;lt;/extension&amp;gt;
 *   &amp;lt;/simpleContent&amp;gt;
 * &amp;lt;/complexType&amp;gt;
 * &lt;/pre&gt;
 *
 *
 */
@SuppressWarnings("all") @XmlAccessorType(XmlAccessType.FIELD) @XmlType(name = "vacation", propOrder =
{
   "value"
}) public class Vacation
{

   @XmlValue protected String value;
   @XmlAttribute(name = "start") @XmlJavaTypeAdapter(Adapter1.class) @XmlSchemaType(name = "date") protected Date start;
   @XmlAttribute(name = "end") @XmlJavaTypeAdapter(Adapter1.class) @XmlSchemaType(name = "date") protected Date end;
   @XmlAttribute(name = "resourceid") protected Integer resourceid;

   /**
    * Gets the value of the value property.
    *
    * @return
    *     possible object is
    *     {@link String }
    *
    */
   public String getValue()
   {
      return value;
   }

   /**
    * Sets the value of the value property.
    *
    * @param value
    *     allowed object is
    *     {@link String }
    *
    */
   public void setValue(String value)
   {
      this.value = value;
   }

   /**
    * Gets the value of the start property.
    *
    * @return
    *     possible object is
    *     {@link String }
    *
    */
   public Date getStart()
   {
      return start;
   }

   /**
    * Sets the value of the start property.
    *
    * @param value
    *     allowed object is
    *     {@link String }
    *
    */
   public void setStart(Date value)
   {
      this.start = value;
   }

   /**
    * Gets the value of the end property.
    *
    * @return
    *     possible object is
    *     {@link String }
    *
    */
   public Date getEnd()
   {
      return end;
   }

   /**
    * Sets the value of the end property.
    *
    * @param value
    *     allowed object is
    *     {@link String }
    *
    */
   public void setEnd(Date value)
   {
      this.end = value;
   }

   /**
    * Gets the value of the resourceid property.
    *
    * @return
    *     possible object is
    *     {@link Integer }
    *
    */
   public Integer getResourceid()
   {
      return resourceid;
   }

   /**
    * Sets the value of the resourceid property.
    *
    * @param value
    *     allowed object is
    *     {@link Integer }
    *
    */
   public void setResourceid(Integer value)
   {
      this.resourceid = value;
   }

}

/*
 * file:       CustomFieldContainer.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2002-20015
 * date:       28/04/2015
 */

/*
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation; either version 2.1 of the License, or (at your
 * option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */

package net.sf.mpxj;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import net.sf.mpxj.common.Pair;
import net.sf.mpxj.mpp.CustomFieldValueItem;

/**
 * Container holding configuration details for all custom fields.
 */
public class CustomFieldContainer implements Iterable<CustomField>
{
   /**
    * Retrieve configuration details for a given custom field.
    *
    * @param field required custom field
    * @return configuration detail
    */
   public CustomField getCustomField(FieldType field)
   {
      return m_configMap.computeIfAbsent(field, k -> new CustomField(field, this));
   }

   /**
    * Return the number of custom fields.
    *
    * @return number of custom fields
    */
   public int size()
   {
      return m_configMap.values().size();
   }

   @Override public Iterator<CustomField> iterator()
   {
      return m_configMap.values().iterator();
   }

   /**
    * Retrieve a custom field value by its unique ID.
    *
    * @param uniqueID custom field value unique ID
    * @return custom field value
    */
   public CustomFieldValueItem getCustomFieldValueItemByUniqueID(int uniqueID)
   {
      return m_valueMap.get(Integer.valueOf(uniqueID));
   }

   /**
    * Retrieve a custom field value by its guid.
    *
    * @param guid custom field value guid
    * @return custom field value
    */
   public CustomFieldValueItem getCustomFieldValueItemByGuid(UUID guid)
   {
      return m_guidMap.get(guid);
   }

   /**
    * Add a value to the custom field value index.
    *
    * @param item custom field value
    */
   public void registerValue(CustomFieldValueItem item)
   {
      m_valueMap.put(item.getUniqueID(), item);
      if (item.getGUID() != null)
      {
         m_guidMap.put(item.getGUID(), item);
      }
   }

   /**
    * Remove a value from the custom field value index.
    *
    * @param item custom field value
    */
   public void deregisterValue(CustomFieldValueItem item)
   {
      m_valueMap.remove(item.getUniqueID());
      if (item.getGUID() != null)
      {
         m_guidMap.remove(item.getGUID());
      }
   }

   /**
    * When an alias for a field is added, index it here to allow lookup by alias and type.
    *
    * @param type field type
    * @param alias field alias
    */
   void registerAlias(FieldType type, String alias)
   {
      m_aliasMap.put(new Pair<>(type.getFieldTypeClass(), alias), type);
   }

   /**
    * Retrieve a field from a particular entity using its alias.
    *
    * @param typeClass the type of entity we are interested in
    * @param alias the alias
    * @return the field type referred to be the alias, or null if not found
    */
   public FieldType getFieldByAlias(FieldTypeClass typeClass, String alias)
   {
      return m_aliasMap.get(new Pair<>(typeClass, alias));
   }

   /**
    * Return a stream of CustomFields.
    *
    * @return Stream instance
    */
   public Stream<CustomField> stream()
   {
      return StreamSupport.stream(spliterator(), false);
   }

   private final Map<FieldType, CustomField> m_configMap = new HashMap<>();
   private final Map<Integer, CustomFieldValueItem> m_valueMap = new HashMap<>();
   private final Map<UUID, CustomFieldValueItem> m_guidMap = new HashMap<>();
   private final Map<Pair<FieldTypeClass, String>, FieldType> m_aliasMap = new HashMap<>();
}

/*
 * file:       AssignmentFlagsTest.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2016
 * date:       2018-10-18
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

package net.sf.mpxj.junit.assignment;

import static net.sf.mpxj.junit.MpxjAssert.*;
import static org.junit.Assert.*;

import java.io.File;

import org.junit.Test;

import net.sf.mpxj.MPXJException;
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.Task;
import net.sf.mpxj.junit.MpxjTestData;
import net.sf.mpxj.mpd.MPDDatabaseReader;
import net.sf.mpxj.mpx.MPXReader;
import net.sf.mpxj.reader.ProjectReader;
import net.sf.mpxj.reader.ProjectReaderUtility;

/**
 * Tests to ensure assignment flags are read correctly.
 */
public class AssignmentFlagsTest
{
   /**
    * Test to validate calendars in files saved by different versions of MS Project.
    */
   @Test public void testAssignments() throws MPXJException
   {
      for (File file : MpxjTestData.listFiles("generated/assignment-flags", "assignment-flags"))
      {
         testAssignmentFlags(file);
      }
   }

   /**
    * Test an individual project.
    *
    * @param file project file
    */
   private void testAssignmentFlags(File file) throws MPXJException
   {
      ProjectReader reader = ProjectReaderUtility.getProjectReader(file.getName());
      if (reader instanceof MPDDatabaseReader && !isMicrosoftAccessJdbcAvailable())
      {
         return;
      }

      int maxIndex = reader instanceof MPXReader ? 10 : 20;
      ProjectFile project = reader.read(file);
      for (int index = 1; index <= maxIndex; index++)
      {
         Task task = project.getTaskByID(Integer.valueOf(index));
         assertEquals("Task " + index, task.getName());
         testAssignmentFlags(file, task, index, maxIndex);
      }
   }

   /**
    * Test the flag values for a task.
    *
    * @param file parent file
    * @param task task
    * @param trueFlagIndex index of flag which is expected to be true
    * @param maxIndex maximum number of custom fields to expect in this file
    */
   private void testAssignmentFlags(File file, Task task, int trueFlagIndex, int maxIndex)
   {
      for (int index = 1; index <= maxIndex; index++)
      {
         Boolean flagValue = Boolean.valueOf(task.getResourceAssignments().get(0).getFlag(index));
         assertEquals(file.getName() + " Flag" + index, Boolean.valueOf(index == trueFlagIndex), flagValue);
      }
   }
}

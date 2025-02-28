/*
 * file:       CleanByReplacementStrategy.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2022
 * date:       03/01/2022
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

package net.sf.mpxj.utility.clean;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Cleans text by replacing it with random replacements words.
 */
public class CleanByReplacementStrategy implements CleanStrategy
{
   /**
    * Constructor.
    */
   public CleanByReplacementStrategy()
   {
      loadDictionary();
   }

   @Override public String generateReplacementText(String oldText)
   {
      StringBuilder sb = new StringBuilder(oldText);
      Matcher matcher = WORD_PATTERN.matcher(oldText);

      while (matcher.find())
      {
         String word = matcher.group(1);
         String replacement;

         if (word.length() > MIN_WORD_LENGTH && isNotNumeric(word))
         {
            replacement = m_words.computeIfAbsent(word, this::generateReplacementWord);
            int end = matcher.end();
            sb.replace(matcher.start(), end, matchCase(word, replacement));
         }
      }

      return sb.toString();
   }

   /**
    * DReturns true if the supplied word is not a numeric value.
    *
    * @param word text to test
    * @return true if the text is not numeric
    */
   private boolean isNotNumeric(String word)
   {
      try
      {
         Double.parseDouble(word);
         return false;
      }

      catch (Exception ex)
      {
         return true;
      }
   }

   /**
    * Generate a replacement word which is different to the supplied word.
    *
    * @param word word to replace
    * @return replacement word
    */
   private String generateReplacementWord(String word)
   {
      Integer key = Integer.valueOf(word.length());
      List<String> words = m_dictionary.get(key);
      String replacement;

      do
      {
         int wordIndex = m_random.nextInt(words.size() - 1);
         replacement = words.get(wordIndex);
      }
      while (replacement.equalsIgnoreCase(word));

      return replacement;
   }

   /**
    * Ensure the case of the replacement word matches the original word.
    *
    * @param oldWord original word
    * @param newWord replacement word
    * @return replacement word with matching case
    */
   private String matchCase(String oldWord, String newWord)
   {
      StringBuilder sb = new StringBuilder(newWord);
      for (int index = 0; index < oldWord.length(); index++)
      {
         if (Character.isUpperCase(oldWord.charAt(index)))
         {
            sb.setCharAt(index, Character.toUpperCase(sb.charAt(index)));
         }
      }
      return sb.toString();
   }

   /**
    * Load the dictionary words.
    */
   private void loadDictionary()
   {
      URL url = getClass().getClassLoader().getResource("net/sf/mpxj/utility/clean/words.txt");
      if (url == null)
      {
         throw new RuntimeException("Unable to load words");
      }

      try (Stream<String> stream = Files.lines(Paths.get(url.toURI())))
      {
         stream.forEach(this::processWord);
      }

      catch (Exception ex)
      {
         throw new RuntimeException(ex);
      }
   }

   /**
    * Populate a map of words keyed by word length.
    *
    * @param word word to add to map
    */
   private void processWord(String word)
   {
      m_dictionary.computeIfAbsent(Integer.valueOf(word.length()), ArrayList::new).add(word);
   }

   private final Map<String, String> m_words = new HashMap<>();
   private final Map<Integer, List<String>> m_dictionary = new HashMap<>();
   private final Random m_random = new Random(8118055L);

   private static final int MIN_WORD_LENGTH = 3;
   private static final Pattern WORD_PATTERN = Pattern.compile("(\\w+)");
}

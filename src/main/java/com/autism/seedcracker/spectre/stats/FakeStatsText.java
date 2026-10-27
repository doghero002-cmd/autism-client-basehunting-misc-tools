// Adapted from cody-raves/Spectre d5a7dd3; GPL-3.0-only. See SPECTRE-NOTICES.txt.
package com.autism.seedcracker.spectre.stats;

public final class FakeStatsText {
   private FakeStatsText() {}

   public static int markerAt(String line, String marker) {
      if (marker.isEmpty()) return -1;
      String text = folded(line);
      boolean wordStart = wordChar(marker.charAt(0));
      boolean wordEnd = wordChar(marker.charAt(marker.length() - 1));

      for (int at = 0; at + marker.length() <= text.length(); at++) {
         int end = at + marker.length();
         if (text.regionMatches(true, at, marker, 0, marker.length())
            && (!wordStart || at <= 0 || !wordChar(text.charAt(at - 1)))
            && (!wordEnd || end >= text.length() || !wordChar(text.charAt(end)))) {
            return at;
         }
      }

      return -1;
   }

   private static String folded(String line) {
      char[] out = line.toCharArray();

      for (int i = 0; i < out.length; i++) {
         int letter = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ".indexOf(out[i]);
         if (letter >= 0) {
            out[i] = (char)(97 + letter);
         }
      }

      return new String(out);
   }

   private static boolean wordChar(char c) {
      return Character.isLetterOrDigit(c) || c == '_';
   }

   public static String fit(String real, String custom) {
      String bare = custom.startsWith("$") ? custom.substring(1).strip() : custom;
      return real.startsWith("$") ? "$" + bare : bare;
   }

   public static String maskText(long seed, String text, int row, int[] number) {
      StringBuilder out = null;
      int i = 0;

      while (i < text.length()) {
         int cp = text.codePointAt(i);
         if (!Character.isDigit(cp)) {
            if (out != null) {
               out.appendCodePoint(cp);
            }

            i += Character.charCount(cp);
         } else {
            int end = i;

            while (true) {
               if (end < text.length()) {
                  int c = text.codePointAt(end);
                  int width = Character.charCount(c);
                  if (Character.isDigit(c)) {
                     end += width;
                     continue;
                  }

                  if ((c == 44 || c == 46) && end + width < text.length() && Character.isDigit(text.codePointAt(end + width))) {
                     end += width;
                     continue;
                  }
               }

               String raw = text.substring(i, end);
               String fake = fakeNumber(seed, row, (int)(number[0]++), raw);
               if (out == null) {
                  if (!fake.equals(raw)) {
                     out = new StringBuilder(text.length()).append(text, 0, i).append(fake);
                  }
               } else {
                  out.append(fake);
               }

               i = end;
               break;
            }
         }
      }

      return out == null ? text : out.toString();
   }

   static String fakeNumber(long seed, int row, int index, String raw) {
      int digits = 0;

      for (int i = 0; i < raw.length(); i++) {
         char c = raw.charAt(i);
         if (c >= '0' && c <= '9') {
            digits++;
         }
      }

      if (digits == 0) {
         return raw;
      } else if (digits > 15) {
         return shiftWide(seed, row, index, raw);
      } else {
         long value = 0L;

         for (int ix = 0; ix < raw.length(); ix++) {
            char c = raw.charAt(ix);
            if (c >= '0' && c <= '9') {
               value = value * 10L + (c - '0');
            }
         }

         long span = pow10(digits);
         long offset = Long.remainderUnsigned(mix(seed ^ row * 1315423911L ^ index * 2654435761L ^ digits), span);
         if (offset == 0L) {
            offset = 1L;
         }

         boolean leadingZero = raw.charAt(0) == '0' && digits > 1;
         long fake = shifted(value, offset, span, digits, leadingZero);
         if (fake == value) {
            fake = shifted(value, offset == span - 1L ? 1L : offset + 1L, span, digits, leadingZero);
         }

         String rendered = Long.toString(fake);
         if (rendered.length() < digits) {
            rendered = "0".repeat(digits - rendered.length()) + rendered;
         }

         StringBuilder result = new StringBuilder(raw.length());
         int cursor = 0;

         for (int ixx = 0; ixx < raw.length(); ixx++) {
            char c = raw.charAt(ixx);
            if (c >= '0' && c <= '9') {
               result.append(rendered.charAt(cursor++));
            } else {
               result.append(c);
            }
         }

         return result.toString();
      }
   }

   private static long shifted(long value, long offset, long span, int digits, boolean leadingZero) {
      long fake = Math.floorMod(value + offset, span);
      if (!leadingZero && digits > 1 && fake < span / 10L) {
         fake += span / 10L;
      }

      return fake;
   }

   private static String shiftWide(long seed, int row, int index, String raw) {
      long salt = mix(seed ^ row * 1315423911L ^ index);
      StringBuilder result = new StringBuilder(raw.length());
      int pos = 0;
      boolean first = true;

      for (int i = 0; i < raw.length(); i++) {
         char c = raw.charAt(i);
         if (c >= '0' && c <= '9') {
            int shift = (int)Long.remainderUnsigned(mix(salt + pos), 9L) + 1;
            int shown = (c - '0' + shift) % 10;
            if (first && shown == 0) {
               shown = shift;
            }

            first = false;
            result.append((char)(48 + shown));
            pos++;
         } else {
            result.append(c);
         }
      }

      return result.toString();
   }

   private static long pow10(int digits) {
      long value = 1L;

      for (int i = 0; i < digits; i++) {
         value *= 10L;
      }

      return value;
   }

   private static long mix(long z) {
      z = (z ^ z >>> 30) * -4658895280553007687L;
      z = (z ^ z >>> 27) * -7723592293110705685L;
      return z ^ z >>> 31;
   }

}

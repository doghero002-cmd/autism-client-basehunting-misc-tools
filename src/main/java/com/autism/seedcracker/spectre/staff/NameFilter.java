// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/util/NameFilter.java
package com.autism.seedcracker.spectre.staff;

import java.util.function.UnaryOperator;
import com.autism.seedcracker.krypton.NameProtectModule;
import net.minecraft.network.chat.Component;

public final class NameFilter {
   private static final UnaryOperator<String> NATIVE = name -> NameProtectModule.active()
      ? NameProtectModule.transform(Component.literal(name)).getString() : name;
   private static volatile UnaryOperator<String> filter = NATIVE;

   private NameFilter() {
   }

   public static String apply(String name) {
      if (name == null) {
         return null;
      } else {
         String shown = filter.apply(name);
         return shown != null ? shown : name;
      }
   }

   public static void set(UnaryOperator<String> newFilter) {
      filter = newFilter != null ? newFilter : NATIVE;
   }
}

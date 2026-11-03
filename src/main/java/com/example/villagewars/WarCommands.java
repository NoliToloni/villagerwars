   package com.example.villagewars;

   import com.mojang.brigadier.arguments.IntegerArgumentType;
   import com.mojang.brigadier.context.CommandContext;
   import net.minecraft.ChatFormatting;
   import net.minecraft.commands.CommandSourceStack;
   import net.minecraft.commands.Commands;
   import net.minecraft.network.chat.Component;
   import net.minecraft.server.level.ServerLevel;
   import net.minecraftforge.event.RegisterCommandsEvent;
   import net.minecraftforge.eventbus.api.SubscribeEvent;

   import java.util.List;

   public class WarCommands {

       @SubscribeEvent
       public void register(RegisterCommandsEvent e) {
           e.getDispatcher().register(Commands.literal("villagewars")
                   .then(Commands.literal("list").executes(this::list))
                   .then(Commands.literal("scan").requires(s -> s.hasPermission(2)).executes(this::scan))
                   .then(Commands.literal("war").requires(s -> s.hasPermission(2))
                           .then(Commands.argument("von", IntegerArgumentType.integer(1))
                                   .then(Commands.argument("gegen", IntegerArgumentType.integer(1)).executes(this::war))))
                   .then(Commands.literal("peace").requires(s -> s.hasPermission(2))
                           .then(Commands.argument("a", IntegerArgumentType.integer(1))
                                   .then(Commands.argument("b", IntegerArgumentType.integer(1)).executes(this::peace))))
                   .then(Commands.literal("boost").requires(s -> s.hasPermission(2))
                           .then(Commands.argument("nr", IntegerArgumentType.integer(1)).executes(this::boost))));
       }

       private int list(CommandContext<CommandSourceStack> c) {
           ServerLevel level = c.getSource().getServer().overworld();
           SettlementData d = SettlementData.get(level);
           if (d.settlements.isEmpty()) {
               c.getSource().sendSuccess(() -> Component.literal("Noch keine Siedlungen entdeckt. Geh in die Nähe eines Dorfes.").withStyle(ChatFormatting.GRAY), false);
               return 0;
           }
           for (int i = 0; i < d.settlements.size(); i++) {
               Settlement s = d.settlements.get(i);
               int nr = i + 1;
               Component line = Component.literal("#" + nr + " ").withStyle(ChatFormatting.DARK_GRAY)
                       .append(Component.literal(s.name).withStyle(Simulation.COLORS[s.colorIndex % Simulation.COLORS.length]))
                       .append(Component.literal(String.format(" @ %d %d | Einw. %d | Soldaten %d | Nahrung %.0f Holz %.0f Eisen %.0f Gold %.0f%s",
                               s.center.getX(), s.center.getZ(), s.population, s.soldiers, s.food, s.wood, s.iron, s.gold,
                               d.atWar(s) ? " | IM KRIEG" : "")).withStyle(ChatFormatting.GRAY));
               c.getSource().sendSuccess(() -> line, false);
               for (int j = 0; j < d.settlements.size(); j++) {
                   if (j == i) continue;
                   Settlement o = d.settlements.get(j);
                   if (Simulation.horizDistSq(s.center, o.center) > (double) Simulation.WAR_RANGE * Simulation.WAR_RANGE) continue;
                   int rel = d.getRelation(s, o);
                   int jj = j + 1;
                   c.getSource().sendSuccess(() -> Component.literal("    -> #" + jj + " " + o.name + ": " + rel).withStyle(
                           rel <= Simulation.WAR_THRESHOLD ? ChatFormatting.RED : rel < 0 ? ChatFormatting.GOLD : ChatFormatting.GREEN), false);
               }
           }
           return d.settlements.size();
       }

       private int scan(CommandContext<CommandSourceStack> c) {
           ServerLevel level = c.getSource().getServer().overworld();
           new Simulation().discover(level);
           return list(c);
       }

       private int war(CommandContext<CommandSourceStack> c) {
           ServerLevel level = c.getSource().getServer().overworld();
           SettlementData d = SettlementData.get(level);
           Settlement a = pick(d.settlements, IntegerArgumentType.getInteger(c, "von"));
           Settlement b = pick(d.settlements, IntegerArgumentType.getInteger(c, "gegen"));
           if (a == null || b == null || a == b) {
               c.getSource().sendFailure(Component.literal("Ungültige Nummern, siehe /villagewars list"));
               return 0;
           }
           if (a.soldiers < 2) a.soldiers = 4; // Befehl soll immer funktionieren
           d.setRelation(a, b, -80);
           new Simulation().launch(level, d, a, b);
           return 1;
       }

       private int peace(CommandContext<CommandSourceStack> c) {
           ServerLevel level = c.getSource().getServer().overworld();
           SettlementData d = SettlementData.get(level);
           Settlement a = pick(d.settlements, IntegerArgumentType.getInteger(c, "a"));
           Settlement b = pick(d.settlements, IntegerArgumentType.getInteger(c, "b"));
           if (a == null || b == null || a == b) {
               c.getSource().sendFailure(Component.literal("Ungültige Nummern, siehe /villagewars list"));
               return 0;
           }
           d.setRelation(a, b, 0);
           c.getSource().sendSuccess(() -> Component.literal("Beziehung zwischen " + a.name + " und " + b.name + " auf 0 gesetzt."), true);
           return 1;
       }

       private int boost(CommandContext<CommandSourceStack> c) {
           ServerLevel level = c.getSource().getServer().overworld();
           SettlementData d = SettlementData.get(level);
           Settlement s = pick(d.settlements, IntegerArgumentType.getInteger(c, "nr"));
           if (s == null) {
               c.getSource().sendFailure(Component.literal("Ungültige Nummer"));
                             return 0;
           }
           s.food += 300; s.wood += 100; s.iron += 100; s.gold += 50;
           d.setDirty();
           c.getSource().sendSuccess(() -> Component.literal(s.name + " hat Vorräte erhalten."), true);
           return 1;
       }

       private Settlement pick(List<Settlement> l, int nr) {
           return nr >= 1 && nr <= l.size() ? l.get(nr - 1) : null;
       }
   }

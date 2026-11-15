package io.embodit.tidetank.domain

/** A zh / en pair (design content: copied verbatim, D28). */
data class Tx(val zh: String, val en: String) { fun get(zh: Boolean) = if (zh) this.zh else en }

/** Creature words (design content.creatures). */
object Creatures {
    data class Info(val name: Tx, val clue: Tx, val fact: Tx, val sci: String, val plain: Tx, val found: Tx)

    val info: Map<Kind, Info> = mapOf(
        Kind.CHROMIS to Info(Tx("青魔", "Green chromis"), Tx("开局就在，撒点浮片打个招呼。", "Already here. Drop some flakes to say hi."),
            Tx("它们一大群一起游，被吓到会一起钻进珊瑚里。", "They hang out in big groups and dive into the coral together when spooked."),
            "绿光雀鲷 Chromis viridis", Tx("撒浮片。", "Drop flakes."), Tx("是青魔！", "Green chromis!")),
        Kind.CLOWN to Info(Tx("小丑鱼", "Clownfish"), Tx("海葵里住着两个小家伙，饭得送到门口。", "Two little guys live in the anemone. Drop dinner at their door."),
            Tx("海葵会蜇别的鱼，但小丑鱼身上有黏液，住在里面没事。", "Anemones sting most fish. Clownfish have a slimy coat, so they live inside just fine."),
            "眼斑双锯鱼 Amphiprion ocellaris", Tx("往海葵边撒沉粒。", "Pellets, right by the anemone."), Tx("是小丑鱼！", "Clownfish!")),
        Kind.CRAB to Info(Tx("寄居蟹", "Hermit crab"), Tx("等落潮，礁石缝里有人会饿。", "Wait for low tide. Someone in the rocks gets hungry."),
            Tx("身子长大了，它就去找一个更大的空螺壳搬家。", "When it outgrows its shell, it moves into a bigger empty one."),
            "寄居蟹总科 Paguroidea", Tx("落潮，往礁石上撒沉粒。", "Low tide, pellets on the rocks."), Tx("是寄居蟹！", "Hermit crab!")),
        Kind.SEAHORSE to Info(Tx("海马", "Seahorse"), Tx("海草里藏着一个站着游泳的，它爱吃活的。", "Something swims upright in the seagrass. It likes live food."),
            Tx("海马是爸爸怀孕，小海马从爸爸的育儿袋里出来。", "Seahorse dads carry the babies in a pouch."),
            "海马属 Hippocampus", Tx("往海草里撒丰年虾。", "Brine shrimp into the seagrass."), Tx("是海马！", "Seahorse!")),
        Kind.TANG to Info(Tx("蓝吊", "Blue tang"), Tx("等涨满潮，外海的门会开，多撒几回浮片。", "At high tide the sea gate opens. Keep the flakes coming."),
            Tx("尾巴根两边各有一根小刀片，惹急了会甩。", "It has a tiny blade on each side of its tail. Don't make it mad."),
            "黄尾副刺尾鱼 Paracanthurus hepatus", Tx("涨潮，撒三回浮片。", "High tide, three rounds of flakes."), Tx("是蓝吊！", "Blue tang!")),
        Kind.JELLY to Info(Tx("海月水母", "Moon jelly"), Tx("涨满潮时，往开阔的水里撒丰年虾，会有个透明的来。", "At high tide, drop brine shrimp in open water. Something see-through shows up."),
            Tx("它没有脑子，也没有心，靠伞一张一合把小东西兜进嘴里。", "No brain, no heart. It just pulses and scoops tiny food in."),
            "海月水母 Aurelia aurita", Tx("涨潮，丰年虾撒在缸中间。", "High tide, brine shrimp mid-tank."), Tx("是海月水母！", "Moon jelly!")),
    )

    fun of(k: Kind) = info.getValue(k)
}

/**
 * What the control window's first row and the base plaque say (design content.plaqueLines + panels.pages.tank row 1).
 * The window shows [panel], the plaque [plaque] (short: ≤ 18 CJK chars / 40 Latin chars).
 */
sealed class Say(val panel: Tx, val plaque: Tx) {
    object Start : Say(Tx("拿起饲料罐，在水面上划一下。", "Grab a food jar and sweep it over the water."), Tx("拿起饲料罐，在水面上划一下。", "Grab the jar and sweep it over the water."))
    object Carry : Say(Tx("在水面上方划，饲料会一路撒下去。", "Sweep over the water. Food drops as you go."), Tx("在水面上方划，饲料一路往下撒。", "Sweep over the water. Food drops as you go."))
    object AfterFirst : Say(Tx("试试别的饲料，或者拨一下潮水。", "Try another food, or move the tide."), Tx("试试别的饲料，或者拨一下潮水。", "Try another food, or move the tide."))
    class SwitchFood(f: Food) : Say(SWITCH[f.ordinal], SWITCH[f.ordinal])
    class Feeding(f: Food) : Say(FEED[f.ordinal], FEED[f.ordinal])
    object Full : Say(Tx("它们吃饱了，过会儿再喂。", "They're full. Try again in a bit."), Tx("它们吃饱了，过会儿再喂。", "They're full. Try again in a bit."))
    class TideSay(t: Tide) : Say(TIDE[t.ordinal], TIDE[t.ordinal])
    class Found(val kind: Kind) : Say(Creatures.of(kind).found, Creatures.of(kind).found)
    object AllFound : Say(Tx("六个都见到了。这缸活了。", "All six found. This tank's alive."), Tx("六个都见到了。这缸活了。", "All six found. This tank's alive."))
    class Back(n: Int) : Say(Tx("欢迎回来。图鉴 $n / 6。", "Welcome back. Guide $n / 6."), Tx("欢迎回来。图鉴 $n / 6。", "Welcome back. Guide $n / 6."))
    /** A clue for [kind]: the long clue in the window, 「试试：」 + plain on the plaque; [plainToo] after 3 times (design hints). */
    class Hint(val kind: Kind, plainToo: Boolean) : Say(
        if (plainToo) Creatures.of(kind).plain else Creatures.of(kind).clue,
        Tx("试试：" + Creatures.of(kind).plain.zh, "Try: " + Creatures.of(kind).plain.en),
    )

    companion object {
        val SWITCH = listOf(Tx("换成浮片了，漂在水面上。", "Flakes now. They float."), Tx("换成沉粒了，会沉到底。", "Pellets now. They sink."),
            Tx("换成丰年虾了，活的，悬在中间。", "Brine shrimp now. Live, they hang mid-water."))
        val FEED = listOf(Tx("青魔冲上来了！", "Here come the chromis!"), Tx("沉粒落到底了，这儿没人要。", "Pellets hit the bottom. No takers here."),
            Tx("丰年虾在乱蹦，看谁来抢。", "Brine shrimp wiggling. Who's hungry?"))
        val TIDE = listOf(Tx("落潮了，礁石露出来了。", "Low tide. The rocks are out."), Tx("平潮。", "Mid tide."), Tx("涨满潮，外海的门开了。", "High tide. The sea gate's open."))
        val NOT_HERE = Tx("它现在不在。", "Not here right now.")
        val SPOT = mapOf(Spot.ANEMONE to Tx("海葵边", "by the anemone"), Spot.ROCKS to Tx("礁石边", "on the rocks"), Spot.GRASS to Tx("海草边", "in the seagrass"), Spot.OPEN to Tx("开阔水", "open water"))
        val TIDE_WORD = listOf(Tx("落潮", "Low tide"), Tx("平潮", "Mid tide"), Tx("涨潮", "High tide"))
        val FOOD_WORD = listOf(Tx("浮片", "flakes"), Tx("沉粒", "pellets"), Tx("丰年虾", "brine shrimp"))

        /** 「怎么遇到的」: 落潮 · 沉粒 · 礁石边 / Low tide · pellets · on the rocks. */
        fun how(d: Discovery, zh: Boolean): String = listOf(TIDE_WORD[d.tide.ordinal].get(zh), FOOD_WORD[d.food.ordinal].get(zh), SPOT.getValue(d.spot).get(zh)).joinToString(" · ")
    }
}

package top.drawtwice.taikocraft.client

import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.common.NeoForge
import net.minecraft.client.KeyMapping as McKeyMapping
import com.mojang.blaze3d.platform.InputConstants
import org.lwjgl.glfw.GLFW
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.chart.SongLibrary
import top.drawtwice.taikocraft.client.input.KeyMapping
import top.drawtwice.taikocraft.client.input.TaikoKeyConfig
import java.nio.file.Files
import java.util.Properties

/**
 * 客户端入口（只在客户端加载）。
 *
 * 设置文件：`.minecraft/taikocraft/settings.properties`
 */
@Mod(value = TaikoCraft.MOD_ID, dist = [Dist.CLIENT])
class TaikoCraftClient(modEventBus: IEventBus) {

    companion object {
        var visualOffsetMs: Double = 0.0
            private set
        var audioOffsetMs: Double = 0.0
            private set

        val openMenuKey: McKeyMapping = McKeyMapping(
            "key.taikocraft.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.taikocraft",
        )

        private var wasPressed = false

        fun setOffsets(visual: Double, audio: Double) {
            visualOffsetMs = visual
            audioOffsetMs = audio
            saveSettings()
        }

        private fun loadSettings() {
            val path = SongLibrary.settingsFile()
            if (!Files.exists(path)) return
            runCatching {
                val p = Properties()
                Files.newInputStream(path).use { p.load(it) }
                visualOffsetMs = p.getProperty("visualOffsetMs")?.toDoubleOrNull() ?: 0.0
                audioOffsetMs = p.getProperty("audioOffsetMs")?.toDoubleOrNull() ?: 0.0
                TaikoKeyConfig.scheme = top.drawtwice.taikocraft.client.input.KeyScheme
                    .fromName(p.getProperty("keyScheme"))
                TaikoKeyConfig.mapping = top.drawtwice.taikocraft.client.input.KeyMapping(
                    p.getProperty("key.leftDon")?.toIntOrNull() ?: 70,
                    p.getProperty("key.leftKa")?.toIntOrNull() ?: 68,
                    p.getProperty("key.rightDon")?.toIntOrNull() ?: 74,
                    p.getProperty("key.rightKa")?.toIntOrNull() ?: 75,
                )
                TaikoCraft.LOGGER.info("设置已加载：目押 {} 音押 {}", visualOffsetMs, audioOffsetMs)
            }.onFailure { TaikoCraft.LOGGER.warn("读取设置失败：{}", it.message) }
        }

        private fun saveSettings() {
            runCatching {
                val path = SongLibrary.settingsFile()
                Files.createDirectories(path.parent)
                val p = Properties()
                p.setProperty("visualOffsetMs", visualOffsetMs.toString())
                p.setProperty("audioOffsetMs", audioOffsetMs.toString())
                p.setProperty("keyScheme", TaikoKeyConfig.scheme.name)
                p.setProperty("key.leftDon", TaikoKeyConfig.mapping.leftDon.toString())
                p.setProperty("key.leftKa", TaikoKeyConfig.mapping.leftKa.toString())
                p.setProperty("key.rightDon", TaikoKeyConfig.mapping.rightDon.toString())
                p.setProperty("key.rightKa", TaikoKeyConfig.mapping.rightKa.toString())
                Files.newOutputStream(path).use { p.store(it, "TaikoCraft settings") }
            }.onFailure { TaikoCraft.LOGGER.warn("保存设置失败：{}", it.message) }
        }
    }

    init {
        modEventBus.addListener(this::onClientSetup)
        modEventBus.addListener(this::onRegisterKeys)
        NeoForge.EVENT_BUS.addListener(this::onClientTick)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        SongLibrary.ensureDirs()
        loadSettings()
        TaikoCraft.LOGGER.info("TaikoCraft 客户端就绪，曲库：{}", SongLibrary.songsDir())
    }

    private fun onRegisterKeys(event: RegisterKeyMappingsEvent) {
        event.register(openMenuKey)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) { wasPressed = true; return }
        val pressed = openMenuKey.isDown
        if (pressed && !wasPressed) {
            mc.setScreen(SongSelectScreen(null))
        }
        wasPressed = pressed
    }
}

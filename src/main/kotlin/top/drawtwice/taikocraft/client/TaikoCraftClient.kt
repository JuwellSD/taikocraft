package top.drawtwice.taikocraft.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import top.drawtwice.taikocraft.TaikoCraft
import java.nio.file.Files
import java.nio.file.Path

/**
 * 客户端入口（**只在客户端加载**）。
 *
 * 采用**显式注册**而不是 `@EventBusSubscriber` 自动扫描，原因见 [TaikoCraft] 的注释。
 *
 * - 构造器注入的 `modEventBus` = **MOD 总线** → FMLClientSetup / RegisterKeyMappings
 * - `NeoForge.EVENT_BUS` = **GAME 总线** → ClientTickEvent
 */
@Mod(value = TaikoCraft.MOD_ID, dist = [Dist.CLIENT])
class TaikoCraftClient(modEventBus: IEventBus) {

    companion object {
        /** 目押偏移（毫秒）：只影响音符**渲染位置** */
        var visualOffsetMs: Double = 0.0
            private set

        /** 音押偏移（毫秒）：只影响**判定时刻** */
        var audioOffsetMs: Double = 0.0
            private set

        val openMenuKey: KeyMapping = KeyMapping(
            "key.taikocraft.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.taikocraft",
        )

        private var wasPressed = false

        private fun settingsPath(): Path = Path.of("config", "taikocraft", "settings.properties")

        /** 设置并持久化两个偏移 */
        fun setOffsets(visual: Double, audio: Double) {
            visualOffsetMs = visual
            audioOffsetMs = audio
            saveSettings()
            TaikoCraft.LOGGER.info("偏移已更新：目押 {} ms，音押 {} ms", visual, audio)
        }

        /** 兼容旧接口：同时设置两者 */
        fun setUniversalOffset(value: Double) = setOffsets(value, value)

        private fun loadSettings() {
            val path = settingsPath()
            if (!Files.exists(path)) return
            runCatching {
                val props = java.util.Properties()
                Files.newInputStream(path).use { props.load(it) }
                // 旧版只有 universalOffsetMs，兼容读取
                val legacy = props.getProperty("universalOffsetMs")?.toDoubleOrNull()
                visualOffsetMs = props.getProperty("visualOffsetMs")?.toDoubleOrNull() ?: legacy ?: 0.0
                audioOffsetMs = props.getProperty("audioOffsetMs")?.toDoubleOrNull() ?: legacy ?: 0.0
                TaikoCraft.LOGGER.info("已加载偏移：目押 {} ms，音押 {} ms", visualOffsetMs, audioOffsetMs)
                // 键位
                val ld = props.getProperty("key.leftDon")?.toIntOrNull()
                val lk = props.getProperty("key.leftKa")?.toIntOrNull()
                val rd = props.getProperty("key.rightDon")?.toIntOrNull()
                val rk = props.getProperty("key.rightKa")?.toIntOrNull()
                if (ld != null && lk != null && rd != null && rk != null) {
                    top.drawtwice.taikocraft.client.input.TaikoKeyConfig.mapping =
                        top.drawtwice.taikocraft.client.input.KeyMapping(ld, lk, rd, rk)
                    top.drawtwice.taikocraft.client.input.TaikoKeyConfig.scheme =
                        top.drawtwice.taikocraft.client.input.KeyScheme.CUSTOM
                    TaikoCraft.LOGGER.info("已加载自定义键位")
                }
                // 材质风格
                NoteSkinManager.current = NoteSkin.fromName(props.getProperty("noteSkin"))
                TaikoCraft.LOGGER.info("已加载材质风格：{}", NoteSkinManager.current.displayName)
            }.onFailure {
                TaikoCraft.LOGGER.warn("读取设置失败：{}", it.message)
            }
        }

        private fun saveSettings() {
            runCatching {
                val path = settingsPath()
                Files.createDirectories(path.parent)
                val props = java.util.Properties()
                props.setProperty("visualOffsetMs", visualOffsetMs.toString())
                props.setProperty("audioOffsetMs", audioOffsetMs.toString())
                // 键位
                val m = top.drawtwice.taikocraft.client.input.TaikoKeyConfig.mapping
                props.setProperty("key.leftDon", m.leftDon.toString())
                props.setProperty("key.leftKa", m.leftKa.toString())
                props.setProperty("key.rightDon", m.rightDon.toString())
                props.setProperty("key.rightKa", m.rightKa.toString())
                props.setProperty("noteSkin", NoteSkinManager.current.name)
                Files.newOutputStream(path).use { props.store(it, "TaikoCraft settings") }
            }.onFailure {
                TaikoCraft.LOGGER.warn("保存设置失败：{}", it.message)
            }
        }
    }

    init {
        // MOD 总线
        modEventBus.addListener(this::onClientSetup)
        modEventBus.addListener(this::onRegisterKeyMappings)
        // GAME 总线
        NeoForge.EVENT_BUS.addListener(this::onClientTick)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        loadSettings()
        TaikoCraft.LOGGER.info("TaikoCraft 客户端就绪，玩家：{}", Minecraft.getInstance().user.name)
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(openMenuKey)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) {
            wasPressed = true // 有界面时吞掉，避免关闭界面时误触发
            return
        }
        val pressed = openMenuKey.isDown
        if (pressed && !wasPressed) {
            mc.setScreen(SongSelectScreen(null))
        }
        wasPressed = pressed
    }
}

package app.rvc.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Пути, которые компилируются заранее: запуск, прокрутка локаций, настройки и обратно. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndHome() = rule.collect(packageName = "com.makdren.rvc", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Локации")), 10_000)

        // Прокрутка главного экрана: список локаций — самый тяжёлый по отрисовке участок.
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            repeat(2) {
                list.fling(Direction.DOWN)
                device.waitForIdle()
            }
            list.fling(Direction.UP)
            device.waitForIdle()
        }

        device.findObject(By.desc("Настройки"))?.let {
            it.click()
            device.wait(Until.hasObject(By.text("Режим работы")), 5_000)
            device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
    }
}

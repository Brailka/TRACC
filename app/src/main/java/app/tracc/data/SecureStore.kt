package app.tracc.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование данных на диске: AES-256-GCM. Подписки, ключи серверов и URL не лежат в открытом
 * виде даже в резервных копиях и дампах файловой системы.
 *
 * Схема «конверта»: файлы шифруются ключом данных, а сам ключ данных хранится зашифрованным
 * ключом из Android Keystore (на устройствах с TEE/StrongBox — аппаратным, он не покидает хранилище).
 * Так на запуск нужна одна операция Keystore вместо одной на каждый файл — каждая из них
 * это вызов системного сервиса, и вместе они заметно задерживали первый кадр.
 */
object SecureStore {
    private const val ALIAS = "tracc-data-key"
    /** Файл зашифрован напрямую ключом Keystore (до 1.0.8): читается, при записи переводится на ключ данных. */
    private const val MAGIC_KEYSTORE: Byte = 0x52 // 'R'
    private const val MAGIC_DATA_KEY: Byte = 0x53 // 'S'
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    @Volatile private var keyFile: File? = null

    /** Где хранить зашифрованный ключ данных. Вызывается до первого чтения. */
    fun bind(dir: File) {
        keyFile = File(dir, "dek.bin")
    }

    private val keystoreKey: SecretKey by lazy { loadOrCreate() }

    private val dataKey: SecretKey by lazy { loadOrCreateDataKey() }

    private fun loadOrCreate(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun loadOrCreateDataKey(): SecretKey {
        val file = keyFile ?: error("SecureStore.bind не вызван")
        if (file.exists()) {
            open(file.readBytes(), keystoreKey, MAGIC_KEYSTORE)?.takeIf { it.size == 32 }?.let { return SecretKeySpec(it, "AES") }
            // Ключ данных не читается (Keystore сброшен): старые файлы всё равно не расшифровать — начинаем заново.
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        atomicWrite(file, seal(raw, keystoreKey, MAGIC_KEYSTORE))
        return SecretKeySpec(raw, "AES")
    }

    private fun seal(plain: ByteArray, key: SecretKey, magic: Byte): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (magic == MAGIC_KEYSTORE) {
            // Keystore сам выбирает IV и не принимает чужой.
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }))
        }
        val body = cipher.doFinal(plain)
        return byteArrayOf(magic) + cipher.iv + body
    }

    private fun open(data: ByteArray, key: SecretKey, magic: Byte): ByteArray? {
        if (data.size < 1 + IV_SIZE + 16 || data[0] != magic) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 1, IV_SIZE))
            cipher.doFinal(data, 1 + IV_SIZE, data.size - 1 - IV_SIZE)
        }.getOrNull()
    }

    fun encrypt(plain: ByteArray): ByteArray = seal(plain, dataKey, MAGIC_DATA_KEY)

    fun decrypt(data: ByteArray): ByteArray? = when (data.firstOrNull()) {
        MAGIC_DATA_KEY -> open(data, dataKey, MAGIC_DATA_KEY)
        MAGIC_KEYSTORE -> open(data, keystoreKey, MAGIC_KEYSTORE)
        else -> null
    }

    fun writeText(file: File, text: String) = atomicWrite(file, encrypt(text.toByteArray(Charsets.UTF_8)))

    fun readText(file: File): String? {
        if (!file.exists()) return null
        return decrypt(file.readBytes())?.let { String(it, Charsets.UTF_8) }
    }

    /** Переписывает файлы старого формата на ключ данных. */
    fun upgrade(files: List<File>) {
        for (file in files) {
            val data = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (data.firstOrNull() != MAGIC_KEYSTORE) continue
            val plain = open(data, keystoreKey, MAGIC_KEYSTORE) ?: continue
            atomicWrite(file, encrypt(plain))
        }
    }

    internal fun atomicWrite(file: File, bytes: ByteArray) {
        val tmp = File(file.path + ".tmp")
        java.io.FileOutputStream(tmp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

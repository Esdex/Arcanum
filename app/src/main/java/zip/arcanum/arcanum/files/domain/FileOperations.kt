package zip.arcanum.arcanum.files.domain

import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileOperations @Inject constructor(
    private val cryptoEngine: VeraCryptEngine
) {
    // TODO: implement file copy, move, rename, delete inside mounted containers
    suspend fun copyFileToContainer(
        sourcePath: String,
        containerId: String,
        containerHandle: Int,
        destinationRelativePath: String
    ): CryptoResult<Unit> {
        TODO("Implement via NativeContainer after JNI bridge is ready")
    }

    suspend fun deleteFileFromContainer(
        containerHandle: Int,
        relativePath: String
    ): CryptoResult<Unit> {
        TODO("Implement via NativeContainer after JNI bridge is ready")
    }
}

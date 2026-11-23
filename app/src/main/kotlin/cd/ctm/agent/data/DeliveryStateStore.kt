package cd.ctm.agent.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ctm.credit.agent.delivery.DeliveryViewModel

/**
 * Conserve localement l'étape en cours de la livraison afin de survivre à un redémarrage
 * ou une mise en arrière-plan pendant l'attente de l'enrôlement MDM.
 */
class DeliveryStateStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ctm_delivery_state_enc",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(state: DeliveryViewModel.State) {
        when (state) {
            is DeliveryViewModel.State.Started -> write(STEP_STARTED, state.deliveryId, "")
            is DeliveryViewModel.State.ImeiVerified -> write(STEP_IMEI_VERIFIED, state.deliveryId, state.imei)
            is DeliveryViewModel.State.ProofRecorded -> write(STEP_PROOF_RECORDED, state.deliveryId, "")
            is DeliveryViewModel.State.EnrollmentRequested -> write(STEP_ENROLLMENT_REQUESTED, state.deliveryId, "")
            is DeliveryViewModel.State.Enrolled -> write(STEP_ENROLLED, state.deliveryId, "")
            is DeliveryViewModel.State.Idle,
            is DeliveryViewModel.State.Completed -> clear()
            is DeliveryViewModel.State.Working,
            is DeliveryViewModel.State.Error -> Unit
        }
    }

    fun load(): DeliveryViewModel.State? {
        val step = prefs.getString(KEY_STEP, null) ?: return null
        val id = prefs.getString(KEY_DELIVERY_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val imei = prefs.getString(KEY_IMEI, "").orEmpty()
        return when (step) {
            STEP_STARTED -> DeliveryViewModel.State.Started(id)
            STEP_IMEI_VERIFIED -> DeliveryViewModel.State.ImeiVerified(id, imei)
            STEP_PROOF_RECORDED -> DeliveryViewModel.State.ProofRecorded(id)
            STEP_ENROLLMENT_REQUESTED -> DeliveryViewModel.State.EnrollmentRequested(id)
            STEP_ENROLLED -> DeliveryViewModel.State.Enrolled(id)
            else -> null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun write(step: String, deliveryId: String, imei: String) {
        prefs.edit()
            .putString(KEY_STEP, step)
            .putString(KEY_DELIVERY_ID, deliveryId)
            .putString(KEY_IMEI, imei)
            .apply()
    }

    private companion object {
        const val KEY_STEP = "step"
        const val KEY_DELIVERY_ID = "delivery_id"
        const val KEY_IMEI = "imei"

        const val STEP_STARTED = "started"
        const val STEP_IMEI_VERIFIED = "imei_verified"
        const val STEP_PROOF_RECORDED = "proof_recorded"
        const val STEP_ENROLLMENT_REQUESTED = "enrollment_requested"
        const val STEP_ENROLLED = "enrolled"
    }
}

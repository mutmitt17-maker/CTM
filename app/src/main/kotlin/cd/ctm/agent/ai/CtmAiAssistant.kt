package cd.ctm.agent.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "CtmAiAssistant"
private const val MODEL_NAME = "gemini-3.8-flash"
private const val TIMEOUT_MS = 60_000L

sealed interface AiOutcome<out T> {
    data class Success<T>(val data: T) : AiOutcome<T>
    data class Error(val message: String) : AiOutcome<Nothing>
}

data class KycIdAnalysisResult(
    val qualityOk: Boolean,
    val qualityScore: Int,
    val qualityFeedback: String,
    val fullName: String,
    val birthDate: String,
    val documentNumber: String,
    val commune: String,
    val neighborhood: String,
    val detectedDocType: String,
)

data class KycSelfieAnalysisResult(
    val qualityOk: Boolean,
    val qualityScore: Int,
    val qualityFeedback: String,
)

data class VoiceCustomerExtraction(
    val firstName: String,
    val lastName: String,
    val phoneNumber: String,
    val nationalId: String,
    val address: String,
    val city: String,
)

data class PortfolioPriorityItem(
    val priority: String,
    val category: String,
    val title: String,
    val reason: String,
    val targetId: String,
    val suggestedAction: String,
)

data class PortfolioBriefResult(
    val headline: String,
    val summary: String,
    val items: List<PortfolioPriorityItem>,
)

/**
 * Assistant IA terrain CTM propulsé par Firebase AI Logic (Gemini 3.8 Flash).
 *
 * Règle métier : l'IA assiste la qualité, l'OCR de pré-remplissage, la dictée vocale
 * et la priorisation de tournée. Elle ne remplace jamais `compute_offer()`, `worker-biometrics`
 * ni les décisions SQL du backend.
 */
class CtmAiAssistant {

    private val ai by lazy { Firebase.ai(backend = GenerativeBackend.googleAI()) }

    private val kycIdModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "qualityOk" to Schema.boolean("True if ID document photo is sharp, well-lit, and readable"),
                "qualityScore" to Schema.integer("Photo clarity score between 0 and 100"),
                "qualityFeedback" to Schema.string("Short actionable French feedback for the field agent"),
                "fullName" to Schema.string("Full name visible on the ID document or empty string"),
                "birthDate" to Schema.string("Birth date formatted strictly YYYY-MM-DD or empty string"),
                "documentNumber" to Schema.string("Document / card number visible or empty string"),
                "commune" to Schema.string("Commune if visible on the ID document or empty string"),
                "neighborhood" to Schema.string("Neighborhood/quartier if visible or empty string"),
                "detectedDocType" to Schema.enumeration(
                    values = listOf("national_id", "voter_card", "passport", "driver_license", "unknown"),
                    description = "Detected DRC document type",
                ),
            ),
        )
        ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            },
        )
    }

    private val selfieQualityModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "qualityOk" to Schema.boolean("True if one face is clearly visible, centered, well-lit, unblurred"),
                "qualityScore" to Schema.integer("Selfie quality score 0..100"),
                "qualityFeedback" to Schema.string("Short French advice to keep or retake the selfie"),
            ),
        )
        ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            },
        )
    }

    private val voiceCustomerModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "firstName" to Schema.string("Customer first name or empty string"),
                "lastName" to Schema.string("Customer last/family name or empty string"),
                "phoneNumber" to Schema.string("DRC phone normalized to +243XXXXXXXXX when present, or empty string"),
                "nationalId" to Schema.string("ID number if mentioned, or empty string"),
                "address" to Schema.string("Street/avenue/commune address if mentioned, or empty string"),
                "city" to Schema.string("City such as Kinshasa, Lubumbashi, Goma, or empty string"),
            ),
        )
        ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            },
        )
    }

    private val portfolioModel by lazy {
        val itemSchema = Schema.obj(
            properties = mapOf(
                "priority" to Schema.enumeration(listOf("URGENT", "HIGH", "NORMAL")),
                "category" to Schema.enumeration(listOf("DELIVERY", "COLLECTION", "KYC", "SYNC")),
                "title" to Schema.string("Short French title naming customer or contract"),
                "reason" to Schema.string("Short French rationale why this comes first"),
                "targetId" to Schema.string("Matching customerId, contractNumber, or applicationId if known"),
                "suggestedAction" to Schema.enumeration(listOf("DELIVER", "COLLECT", "KYC", "NONE")),
            ),
        )
        val schema = Schema.obj(
            properties = mapOf(
                "headline" to Schema.string("One-line French tour focus summary"),
                "summary" to Schema.string("2-sentence practical French briefing for the agent"),
                "items" to Schema.array(itemSchema, "Top up to 5 prioritized actions"),
            ),
        )
        ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            },
        )
    }

    private val pingModel by lazy {
        ai.generativeModel(modelName = MODEL_NAME)
    }

    suspend fun analyzeKycIdPhoto(bitmap: Bitmap, expectedDocType: String): AiOutcome<KycIdAnalysisResult> =
        runSafe {
            val scaled = scaleForAi(bitmap)
            val response = withTimeout(TIMEOUT_MS) {
                kycIdModel.generateContent(
                    content {
                        image(scaled)
                        text(
                            "Tu aides un agent de terrain CTM en RDC à vérifier la qualité d'une photo de pièce d'identité ($expectedDocType) " +
                                "et à pré-remplir les champs textuels lisibles. Évalue si la photo est nette, sans reflet bloquant, non coupée " +
                                "et lisible pour une vérification serveur ultérieure. N'invente jamais une date ou un nom illisible : mets une chaîne vide.",
                        )
                    },
                )
            }
            val json = JSONObject(response.text ?: error("Réponse IA vide."))
            AiOutcome.Success(
                KycIdAnalysisResult(
                    qualityOk = json.optBoolean("qualityOk", false),
                    qualityScore = json.optInt("qualityScore", 0),
                    qualityFeedback = json.optString("qualityFeedback", "Vérifiez la netteté de la pièce."),
                    fullName = json.optString("fullName", "").trim(),
                    birthDate = json.optString("birthDate", "").trim(),
                    documentNumber = json.optString("documentNumber", "").trim(),
                    commune = json.optString("commune", "").trim(),
                    neighborhood = json.optString("neighborhood", "").trim(),
                    detectedDocType = json.optString("detectedDocType", "unknown").trim(),
                ),
            )
        }

    suspend fun analyzeKycSelfieQuality(bitmap: Bitmap): AiOutcome<KycSelfieAnalysisResult> =
        runSafe {
            val scaled = scaleForAi(bitmap)
            val response = withTimeout(TIMEOUT_MS) {
                selfieQualityModel.generateContent(
                    content {
                        image(scaled)
                        text(
                            "Tu vérifies la qualité technique d'un selfie client avant envoi au serveur KYC (net, visage entier bien éclairé, " +
                                "une seule personne, pas de contre-jour ni lunettes sombres). Donne un retour court en français.",
                        )
                    },
                )
            }
            val json = JSONObject(response.text ?: error("Réponse IA vide."))
            AiOutcome.Success(
                KycSelfieAnalysisResult(
                    qualityOk = json.optBoolean("qualityOk", false),
                    qualityScore = json.optInt("qualityScore", 0),
                    qualityFeedback = json.optString("qualityFeedback", "Vérifiez la netteté du selfie."),
                ),
            )
        }

    suspend fun parseCustomerVoiceTranscript(transcript: String): AiOutcome<VoiceCustomerExtraction> =
        runSafe {
            val clean = transcript.trim()
            if (clean.isBlank()) return@runSafe AiOutcome.Error("Dictez ou saisissez d'abord le texte du client.")
            val response = withTimeout(TIMEOUT_MS) {
                voiceCustomerModel.generateContent(
                    "Extrais les informations client de cette dictée terrain en RDC (français / contexte congolais). " +
                        "Normalise le numéro de téléphone au format +243XXXXXXXXX si 9 chiffres après 0 ou 243 sont présents. " +
                        "Laisse vide tout champ absent.\nDictée : \"$clean\"",
                )
            }
            val json = JSONObject(response.text ?: error("Réponse IA vide."))
            AiOutcome.Success(
                VoiceCustomerExtraction(
                    firstName = json.optString("firstName", "").trim(),
                    lastName = json.optString("lastName", "").trim(),
                    phoneNumber = json.optString("phoneNumber", "").trim(),
                    nationalId = json.optString("nationalId", "").trim(),
                    address = json.optString("address", "").trim(),
                    city = json.optString("city", "").trim(),
                ),
            )
        }

    suspend fun prioritizePortfolio(portfolioSnapshot: String): AiOutcome<PortfolioBriefResult> =
        runSafe {
            val response = withTimeout(TIMEOUT_MS) {
                portfolioModel.generateContent(
                    "Tu es l'assistant de tournée d'un agent CTM Crédit Téléphone (USD). " +
                        "Analyse ces données réelles du portefeuille et classe jusqu'à 5 actions prioritaires pour aujourd'hui : " +
                        "1) dossiers 'approved' à livrer rapidement, 2) contrats 'defaulted'/'breached' ou actifs à encaisser, " +
                        "3) clients hors-ligne / KYC à compléter ou dossiers 'pending_review'. Réponds uniquement à partir des données fournies.\n\n" +
                        portfolioSnapshot,
                )
            }
            val json = JSONObject(response.text ?: error("Réponse IA vide."))
            val arr = json.optJSONArray("items")
            val items = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        add(
                            PortfolioPriorityItem(
                                priority = o.optString("priority", "NORMAL"),
                                category = o.optString("category", "KYC"),
                                title = o.optString("title", "Action à suivre"),
                                reason = o.optString("reason", ""),
                                targetId = o.optString("targetId", ""),
                                suggestedAction = o.optString("suggestedAction", "NONE"),
                            ),
                        )
                    }
                }
            }
            AiOutcome.Success(
                PortfolioBriefResult(
                    headline = json.optString("headline", "Synthèse de tournée"),
                    summary = json.optString("summary", ""),
                    items = items,
                ),
            )
        }

    suspend fun probeConnection(): AiOutcome<String> = runSafe {
        val response = withTimeout(TIMEOUT_MS) {
            pingModel.generateContent("Réponds uniquement par : OK")
        }
        val text = response.text?.trim().orEmpty()
        if (text.isBlank()) AiOutcome.Error("Réponse Gemini vide.")
        else AiOutcome.Success(text)
    }

    private inline fun <T> runSafe(block: () -> AiOutcome<T>): AiOutcome<T> = try {
        block()
    } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Firebase AI generation timed out", e)
        AiOutcome.Error("Délai dépassé (60 s) lors de l'appel IA. Vérifiez la connexion et réessayez.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Firebase AI generation failed", e)
        AiOutcome.Error(e.localizedMessage ?: "Service IA indisponible pour le moment.")
    }

    private fun scaleForAi(src: Bitmap, maxSide: Int = 1280): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(src, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }
}

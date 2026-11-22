package cd.ctm.agent

/**
 * Adresse du projet Supabase et clé publique (« anon »). Ce sont les mêmes valeurs que dans web/config.js.
 * La clé anon est faite pour être publique ; ne mettez JAMAIS la clé « service_role » ici.
 */
object AppConfig {
    const val SUPABASE_URL = "https://yoosjqstxqplfjltfosa.supabase.co"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inlvb3NqcXN0eHFwbGZqbHRmb3NhIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ2ODAxMjksImV4cCI6MjExMDI1NjEyOX0.b-4YneL3fJr6c364OSMMHVuYce9f2f_ErDf8_2GOyd4"
}

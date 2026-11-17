package co.getswan.gallerysample

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URL
import java.util.Date
import kotlin.concurrent.thread

/** The sample's sign-in: email and name make a Swan session, then the gallery opens.
 *  A session kept on the phone is offered back with "Welcome back". A real app has its own sign-in. */
class SignInActivity : AppCompatActivity() {
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var signedInLabel: TextView
    private lateinit var continueButton: Button
    private lateinit var switchButton: Button
    private lateinit var emailField: EditText
    private lateinit var nameField: EditText
    private lateinit var errorLabel: TextView
    private lateinit var button: Button
    private lateinit var spinner: ProgressBar
    private lateinit var footer: TextView

    private var existing: SwanSession? = null
    private var busy = false

    // The gallery screen comes back only when the page failed to open or the token died.
    private val openGallery = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val message = result.data?.getStringExtra(GalleryActivity.EXTRA_ERROR)
        if (message != null) showPageError(message) else setBusy(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.signIn)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        signedInLabel = findViewById(R.id.signedInLabel)
        continueButton = findViewById(R.id.continueButton)
        switchButton = findViewById(R.id.switchButton)
        emailField = findViewById(R.id.emailField)
        nameField = findViewById(R.id.nameField)
        errorLabel = findViewById(R.id.errorLabel)
        button = findViewById(R.id.button)
        spinner = findViewById(R.id.spinner)
        footer = findViewById(R.id.footer)

        footer.text = getString(R.string.sign_in_footer, URL(SwanEnvironment.sessionUrl).host)
        continueButton.setOnClickListener { continueExisting() }
        switchButton.setOnClickListener { showForm() }
        button.setOnClickListener { submit() }

        existing = SwanSession.load(this)
        existing?.let { showSignedIn(it) } ?: showForm()
    }

    // MARK: states

    private fun showSignedIn(session: SwanSession) {
        title.text = getString(R.string.welcome_back)
        subtitle.text = getString(R.string.welcome_back_text)
        val until = DateFormat.getTimeFormat(this).format(Date(session.expiresAt))
        signedInLabel.text = getString(R.string.signed_in_as, session.email, until)
        for (v in listOf(emailField, nameField, errorLabel, button, spinner)) v.visibility = View.GONE
        for (v in listOf(signedInLabel, continueButton, switchButton)) v.visibility = View.VISIBLE
        continueButton.text = getString(R.string.continue_button)
    }

    private fun showForm() {
        existing = null
        title.text = getString(R.string.sign_in_title)
        subtitle.text = getString(R.string.sign_in_text)
        for (v in listOf(signedInLabel, continueButton, switchButton, errorLabel, spinner)) v.visibility = View.GONE
        for (v in listOf(emailField, nameField, button)) v.visibility = View.VISIBLE
        button.text = getString(R.string.continue_button)
        emailField.requestFocus()
    }

    /** Signed in: a spinner until the page is ready, so a slow load is not a white screen. */
    private fun beginOpeningGallery(session: SwanSession) {
        setBusy(true, getString(R.string.opening_gallery))
        openGallery.launch(Intent(this, GalleryActivity::class.java))
    }

    /** The page will not open: shows the error here, with Try again. */
    private fun showPageError(message: String) {
        setBusy(false)
        errorLabel.text = message
        errorLabel.visibility = View.VISIBLE
        button.text = getString(R.string.try_again)
        continueButton.text = getString(R.string.try_again)
    }

    private fun setBusy(value: Boolean, label: String = getString(R.string.continue_button)) {
        busy = value
        spinner.visibility = if (value) View.VISIBLE else View.GONE
        if (value) errorLabel.visibility = View.GONE
        for (b in listOf(button, continueButton)) { b.isEnabled = !value; b.text = label }
        switchButton.isEnabled = !value
        emailField.isEnabled = !value
        nameField.isEnabled = !value
    }

    // MARK: actions

    private fun continueExisting() {
        val session = existing ?: return showForm()
        beginOpeningGallery(session)
    }

    private fun submit() {
        if (busy) return
        val email = emailField.text.toString().trim()
        val name = nameField.text.toString().trim()
        if (!email.contains("@") || email.length < 5) return showError(getString(R.string.error_email))
        if (name.isEmpty()) return showError(getString(R.string.error_name))
        setBusy(true, getString(R.string.signing_in))
        thread {
            val result = SwanSession.create(email, name)
            runOnUiThread {
                result.onSuccess { SwanSession.save(this, it); existing = it; beginOpeningGallery(it) }
                    .onFailure { setBusy(false); showError(it.message ?: getString(R.string.error_sign_in)) }
            }
        }
    }

    private fun showError(message: String) {
        errorLabel.text = message
        errorLabel.visibility = View.VISIBLE
    }
}

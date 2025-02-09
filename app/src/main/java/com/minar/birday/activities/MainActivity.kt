package com.minar.birday.activities

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioAttributes.Builder
import android.net.Uri
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.getActionButton
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.datetime.datePicker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.minar.birday.R
import com.minar.birday.adapters.EventAdapter
import com.minar.birday.backup.BirdayImporter
import com.minar.birday.backup.ContactsImporter
import com.minar.birday.model.Event
import com.minar.birday.utilities.AppRater
import com.minar.birday.utilities.checkString
import com.minar.birday.utilities.smartCapitalize
import com.minar.birday.viewmodels.MainViewModel
import kotlinx.android.synthetic.main.dialog_insert_event.view.*
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*

class MainActivity : AppCompatActivity() {
    lateinit var mainViewModel: MainViewModel
    private lateinit var adapter: EventAdapter
    private lateinit var sharedPrefs: SharedPreferences

    @ExperimentalStdlibApi
    override fun onCreate(savedInstanceState: Bundle?) {
        mainViewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        adapter = EventAdapter(null)

        // Create the notification channel and check the permission (note: appIntro 6.0 is still buggy, better avoid to use it for asking permissions)
        askContactsPermission()
        createNotificationChannel()

        // Retrieve the shared preferences
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val theme = sharedPrefs.getString("theme_color", "system")
        val accent = sharedPrefs.getString("accent_color", "brown")

        // Show the introduction for the first launch
        if (sharedPrefs.getBoolean("first", true)) {
            val editor = sharedPrefs.edit()
            editor.putBoolean("first", false)
            editor.apply()
            val intent = Intent(this, WelcomeActivity::class.java)
            startActivity(intent)
            finish()
        }

        // Set the base theme and the accent
        when (theme) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        when (accent) {
            "blue" -> setTheme(R.style.AppTheme_Blue)
            "green" -> setTheme(R.style.AppTheme_Green)
            "orange" -> setTheme(R.style.AppTheme_Orange)
            "yellow" -> setTheme(R.style.AppTheme_Yellow)
            "teal" -> setTheme(R.style.AppTheme_Teal)
            "violet" -> setTheme(R.style.AppTheme_Violet)
            "pink" -> setTheme(R.style.AppTheme_Pink)
            "lightBlue" -> setTheme(R.style.AppTheme_LightBlue)
            "red" -> setTheme(R.style.AppTheme_Red)
            "lime" -> setTheme(R.style.AppTheme_Lime)
            "crimson" -> setTheme(R.style.AppTheme_Crimson)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get the bottom navigation bar and configure it for the navigation plugin
        val navigation = findViewById<BottomNavigationView>(R.id.navigation)
        val navController: NavController = Navigation.findNavController(this,
            R.id.navHostFragment
        )
        navigation.setupWithNavController(navController)
        navigation.setOnNavigationItemReselectedListener {
            // Just ignore the reselection of the same item
        }

        // Rating stuff
        AppRater.appLaunched(this)

        // Manage the fab
        val fab = findViewById<FloatingActionButton>(R.id.fab)
        fab.setOnClickListener {
            vibrate()
            // Show a bottom sheet containing the form to insert a new event
            var nameValue  = "error"
            var surnameValue = ""
            var eventDateValue: LocalDate = LocalDate.of(1970,1,1)
            var countYearValue = true
            val dialog = MaterialDialog(this, BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                cornerRadius(res = R.dimen.rounded_corners)
                title(R.string.new_event)
                icon(R.drawable.ic_party_24dp)
                message(R.string.new_event_description)
                // Don't use scrollable here, instead use a nestedScrollView in the layout
                customView(R.layout.dialog_insert_event)
                positiveButton(R.string.insert_event) {
                    // Use the data to create a event object and insert it in the db
                    val tuple = Event(
                        id = 0, originalDate = eventDateValue, name = nameValue.smartCapitalize(),
                        surname = surnameValue.smartCapitalize(), yearMatter = countYearValue
                    )
                    // Insert using another thread
                    val thread = Thread { mainViewModel.insert(tuple) }
                    thread.start()
                    dismiss()
                }

                negativeButton(R.string.cancel) {
                    dismiss()
                }
            }

            // Setup listeners and checks on the fields
            dialog.getActionButton(WhichButton.POSITIVE).isEnabled = false
            val customView = dialog.getCustomView()
            val name = customView.findViewById<TextView>(R.id.nameEvent)
            val surname = customView.findViewById<TextView>(R.id.surnameEvent)
            val eventDate = customView.findViewById<TextView>(R.id.dateEvent)
            val countYear = customView.findViewById<SwitchMaterial>(R.id.countYearSwitch)
            val endDate = Calendar.getInstance()
            val formatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            var dateDialog: MaterialDialog? = null

            // To automatically show the last selected date, parse it to another Calendar object
            val lastDate = Calendar.getInstance()

            // Update the boolean value on each click
            countYear.setOnCheckedChangeListener { _, isChecked ->
                countYearValue = isChecked
            }

            eventDate.setOnClickListener {
                // Prevent double dialogs on fast click
                if(dateDialog == null) {
                    dateDialog = MaterialDialog(this).show {
                        cancelable(false)
                        cancelOnTouchOutside(false)
                        datePicker(maxDate = endDate, currentDate = lastDate) { _, date ->
                            val year = date.get(Calendar.YEAR)
                            val month = date.get(Calendar.MONTH) + 1
                            val day = date.get(Calendar.DAY_OF_MONTH)
                            eventDateValue = LocalDate.of(year, month, day)
                            eventDate.text = eventDateValue.format(formatter)
                            // If ok is pressed, the last selected date is saved if the dialog is reopened
                            lastDate.set(year, month - 1, day)
                        }
                    }
                    Handler(Looper.getMainLooper()).postDelayed({ dateDialog = null }, 750)
                }
            }

            // Validate each field in the form with the same watcher
            var nameCorrect = false
            var surnameCorrect = true // Surname is not mandatory
            var eventDateCorrect = false
            val watcher = object: TextWatcher {
                override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
                override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}
                override fun afterTextChanged(editable: Editable) {
                    when {
                        editable === name.editableText -> {
                            val nameText = name.text.toString()
                            if (nameText.isBlank() || !checkString(nameText)) {
                                // Setting the error on the layout is important to make the properties work. Kotlin synthetics are being used here
                                customView.nameEventLayout.error = getString(R.string.invalid_value_name)
                                dialog.getActionButton(WhichButton.POSITIVE).isEnabled = false
                                nameCorrect = false
                            }
                            else {
                                nameValue = nameText
                                customView.nameEventLayout.error = null
                                nameCorrect = true
                            }
                        }
                        editable === surname.editableText -> {
                            val surnameText = surname.text.toString()
                            if (!checkString(surnameText)) {
                                // Setting the error on the layout is important to make the properties work. Kotlin synthetics are being used here
                                customView.surnameEventLayout.error = getString(R.string.invalid_value_name)
                                dialog.getActionButton(WhichButton.POSITIVE).isEnabled = false
                                surnameCorrect = false
                            }
                            else {
                                surnameValue = surnameText
                                customView.surnameEventLayout.error = null
                                surnameCorrect = true
                            }
                        }
                        // Once selected, the date can't be blank anymore
                        editable === eventDate.editableText -> eventDateCorrect = true
                    }
                    if(eventDateCorrect && nameCorrect && surnameCorrect) dialog.getActionButton(WhichButton.POSITIVE).isEnabled = true
                }
            }

            name.addTextChangedListener(watcher)
            surname.addTextChangedListener(watcher)
            eventDate.addTextChangedListener(watcher)
        }
    }

    // Create the NotificationChannel. When created the first time, this code does nothing
    private fun createNotificationChannel() {
        val soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + applicationContext.packageName + "/" + R.raw.birday_notification)
        val attributes: AudioAttributes = Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val name = getString(R.string.events_notification_channel)
        val descriptionText = getString(R.string.events_channel_description)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("events_channel", name, importance).apply { description = descriptionText }
        // Additional tuning over sound, vibration and notification light
        channel.setSound(soundUri, attributes)
        channel.enableLights(true)
        channel.lightColor = Color.GREEN
        channel.enableVibration(true)
        // Register the channel with the system
        val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    // Choose a backup registering a callback and following the latest guidelines
    val selectBackup = registerForActivityResult(ActivityResultContracts.GetContent())  { fileUri: Uri? ->
        try {
            val birdayImporter = BirdayImporter(this, null)
            if (fileUri != null) birdayImporter.importBirthdays(this, fileUri)
        }
        catch (e: IOException) {
            e.printStackTrace()
        }
    }


    // Some utility functions, used from every fragment connected to this activity

    // Vibrate using a standard vibration pattern
    fun vibrate() {
        val vib = this.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (sharedPrefs.getBoolean("vibration", true)) // Vibrate if the vibration in options is set to on
            vib.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // Ask contacts permission
    fun askContactsPermission(code: Int = 101): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), code)
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            }
        else true
    }

    // Manage user response to permission requests
    override fun onRequestPermissionsResult(requestCode : Int, permissions: Array<String>, grantResults: IntArray){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            // Contacts at startup, show a toast only for permission denied (don't ask again not selected)
            101 -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS))
                        Toast.makeText(this, getString(R.string.missing_permission_contacts), Toast.LENGTH_LONG).show()
                }
            }
            // Contacts while trying to import from contacts
            102 -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS))
                        Toast.makeText(this, getString(R.string.missing_permission_contacts), Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, getString(R.string.missing_permission_contacts_forever), Toast.LENGTH_LONG).show()
                }
                else {
                    val contactImporter = ContactsImporter(this, null)
                    contactImporter.importContacts(this)
                }
            }
        }
    }
}

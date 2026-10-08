@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.api.account.OAuthProvider
import com.getinsiteview.api.account.SignInError
import com.getinsiteview.api.account.SignInMethod
import com.getinsiteview.core.WebLinks
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.getinsiteview.design.Wordmark
import com.getinsiteview.features.account.SignInReason
import com.getinsiteview.features.app.AppDependencies
import kotlinx.coroutines.launch
import com.getinsiteview.features.R as FeaturesR

/**
 * Sign-in (IOS-M3-01), as a sheet: Google, Microsoft and Apple (in a Custom Tab, when the server
 * has them; docs/PLAN.md §6: Apple signs in on the web here), then email and password. "Forgot
 * password?" opens the web. Creating an account accepts the current terms.
 *
 * @param onFinished signed in: the sheet closes.
 * @param onDismiss closed without signing in (Cancel, back, swipe down).
 */
@Composable
fun SignInView(
    dependencies: AppDependencies,
    reason: SignInReason,
    onFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val account = dependencies.account
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<OAuthProvider>>(emptyList()) }
    var error by remember { mutableStateOf<SignInError?>(null) }
    var busy by remember { mutableStateOf<SignInMethod?>(null) }
    var showsEmail by rememberSaveable { mutableStateOf(false) }
    // No closing the sheet while a sign-in runs (iOS `interactiveDismissDisabled`).
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || busy == null },
    )

    LaunchedEffect(Unit) {
        // Apple first, as on iOS (its native button is above Google and Microsoft there).
        providers = account.oauthProviders().sortedBy { it != OAuthProvider.APPLE }
    }

    fun run(method: SignInMethod, operation: suspend () -> Unit) {
        scope.launch {
            busy = method
            error = null
            try {
                operation()
                onFinished()
            } catch (failure: SignInError) {
                if (failure != SignInError.Cancelled) error = failure
            } finally {
                busy = null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (busy == null) onDismiss() },
        sheetState = sheetState,
        containerColor = Palette.background,
        dragHandle = null,
    ) {
        if (showsEmail) {
            BackHandler { showsEmail = false }
            EmailSignInView(dependencies, onBack = { showsEmail = false }, finished = onFinished)
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton(onClick = onDismiss, enabled = busy == null) {
                        Text(stringResource(FeaturesR.string.cancel), color = Palette.accent)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    SignInHeader(reason)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (provider in providers) {
                            BusyButton(busy = busy == SignInMethod.Provider(provider)) {
                                SecondaryActionButton(
                                    text = providerTitle(provider),
                                    enabled = busy == null,
                                    onClick = {
                                        val appContext = context.applicationContext
                                        run(SignInMethod.Provider(provider)) {
                                            account.signIn(provider) { url -> OAuthCallbackBroker.authenticate(appContext, url) }
                                        }
                                    },
                                )
                            }
                        }
                        SecondaryActionButton(
                            text = stringResource(FeaturesR.string.continue_with_email),
                            enabled = busy == null,
                            onClick = { showsEmail = true },
                        )
                    }
                    error?.let { SignInErrorText(it) }
                    TermsNotice(dependencies.webLinks)
                }
            }
        }
    }
}

@Composable
private fun SignInHeader(reason: SignInReason) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (reason) {
            is SignInReason.SaveBuilding -> {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(44.dp))
                Text(
                    stringResource(FeaturesR.string.save_x, reason.name).uppercase(),
                    style = IvType.display(26.sp),
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.app_a_free_account_keeps_your_buildings_on_any_phone),
                    style = IvType.body(),
                    color = Palette.muted,
                    textAlign = TextAlign.Center,
                )
            }
            SignInReason.Account -> {
                Wordmark(Modifier.padding(bottom = 8.dp), size = 34.sp)
                Text(
                    stringResource(FeaturesR.string.sign_in_to_insite_view).uppercase(),
                    style = IvType.display(22.sp),
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(FeaturesR.string.see_your_company_s_buildings_the_ones_shared_with_you_and_th),
                    style = IvType.body(),
                    color = Palette.muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A button with a spinner at its start while [busy]. */
@Composable
private fun BusyButton(busy: Boolean, button: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart) {
        button()
        if (busy) {
            CircularProgressIndicator(Modifier.padding(start = 16.dp).size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
        }
    }
}

/** Email and password: sign in or create an account (IOS-M3-01). */
@Composable
private fun EmailSignInView(dependencies: AppDependencies, onBack: () -> Unit, finished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(EmailMode.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<SignInError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    val emailOK = email.contains("@") && email.length >= 3
    val canSubmit = when (mode) {
        EmailMode.SIGN_IN -> emailOK && password.isNotEmpty()
        EmailMode.REGISTER -> emailOK && password.length >= 10 && name.trim(' ', '\t').isNotEmpty()
    }

    fun submit() {
        if (!canSubmit || busy) return
        scope.launch {
            busy = true
            error = null
            try {
                when (mode) {
                    EmailMode.SIGN_IN -> dependencies.account.signIn(email, password)
                    EmailMode.REGISTER -> dependencies.account.register(email, password, name)
                }
                finished()
            } catch (failure: SignInError) {
                if (failure != SignInError.Cancelled) {
                    error = failure
                    // iOS also clears the error when the mode changes, which hides this message at once;
                    // here it stays: "There's already an account with this email. Sign in instead."
                    if (failure == SignInError.EmailTaken) mode = EmailMode.SIGN_IN
                }
            } finally {
                busy = false
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.accent,
        unfocusedBorderColor = Palette.line,
        focusedContainerColor = Palette.surface,
        unfocusedContainerColor = Palette.surface,
        cursorColor = Palette.accent,
    )

    Column(Modifier.fillMaxWidth().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.app_back))
            }
            Text(
                stringResource(if (mode == EmailMode.SIGN_IN) FeaturesR.string.sign_in_with_email else FeaturesR.string.create_an_account),
                style = IvType.headline,
                color = Palette.ink,
            )
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val modes = EmailMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = item == mode,
                        onClick = {
                            if (item != mode) {
                                mode = item
                                error = null
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        icon = {},
                        label = {
                            Text(stringResource(if (item == EmailMode.SIGN_IN) FeaturesR.string.sign_in else FeaturesR.string.create_account))
                        },
                    )
                }
            }

            if (mode == EmailMode.REGISTER) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).semantics { contentType = ContentType.PersonFullName },
                    label = { Text(stringResource(FeaturesR.string.your_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { emailFocus.requestFocus() }),
                    colors = fieldColors,
                )
                FieldErrors(error, "displayName")
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth().focusRequester(emailFocus).semantics { contentType = ContentType.Username },
                label = { Text(stringResource(FeaturesR.string.email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                colors = fieldColors,
            )
            FieldErrors(error, "email")
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).semantics {
                    contentType = if (mode == EmailMode.REGISTER) ContentType.NewPassword else ContentType.Password
                },
                label = { Text(stringResource(FeaturesR.string.password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                colors = fieldColors,
                supportingText = if (mode == EmailMode.REGISTER) {
                    { Text(stringResource(FeaturesR.string.at_least_10_characters)) }
                } else {
                    null
                },
            )
            FieldErrors(error, "password")

            BusyButton(busy = busy) {
                PrimaryActionButton(
                    text = stringResource(if (mode == EmailMode.SIGN_IN) FeaturesR.string.sign_in else FeaturesR.string.create_account),
                    enabled = canSubmit && !busy,
                    onClick = { submit() },
                )
            }
            if (mode == EmailMode.SIGN_IN) {
                TextButton(onClick = { WebPages.open(context, dependencies.webLinks.forgotPassword) }) {
                    Text(stringResource(FeaturesR.string.forgot_password), color = Palette.accent)
                }
            }
            error?.takeUnless(::isFieldError)?.let { SignInErrorText(it) }
            if (mode == EmailMode.REGISTER) TermsNotice(dependencies.webLinks)
        }
    }
}

private enum class EmailMode { SIGN_IN, REGISTER }

/** Validation messages for one field: they come from the API in the user's language. */
@Composable
private fun FieldErrors(error: SignInError?, field: String) {
    for (message in error?.messages(field).orEmpty()) {
        Text(message, style = IvType.body(13.sp), color = Palette.warn)
    }
}

private fun isFieldError(error: SignInError): Boolean =
    listOf("displayName", "email", "password").any { error.messages(it).isNotEmpty() }

/** A sign-in error in words (never the API's message; A-02). */
@Composable
fun SignInErrorText(error: SignInError, modifier: Modifier = Modifier) {
    val text = when (error) {
        SignInError.InvalidCredentials -> stringResource(FeaturesR.string.that_email_and_password_don_t_match)
        SignInError.EmailTaken -> stringResource(FeaturesR.string.there_s_already_an_account_with_this_email_sign_in_instead)
        is SignInError.LockedOut -> error.retryAfter?.let { seconds ->
            val minutes = maxOf(1, (seconds + 59) / 60)
            pluralStringResource(FeaturesR.plurals.too_many_tries_try_again_in_n_minute, minutes, minutes)
        } ?: stringResource(FeaturesR.string.too_many_tries_try_again_later)
        SignInError.EmailInUse -> stringResource(FeaturesR.string.this_email_already_has_an_account_sign_in_with_your_email_an)
        SignInError.CodeExpired -> stringResource(FeaturesR.string.signing_in_took_too_long_try_again)
        SignInError.ProviderFailed -> stringResource(FeaturesR.string.signing_in_didn_t_work_try_again)
        SignInError.ProviderUnavailable -> stringResource(FeaturesR.string.this_way_of_signing_in_isn_t_available_right_now)
        is SignInError.Validation -> stringResource(FeaturesR.string.check_the_details_and_try_again)
        is SignInError.RateLimited -> stringResource(FeaturesR.string.too_many_tries_wait_a_moment_then_try_again)
        SignInError.Offline -> stringResource(FeaturesR.string.you_re_offline_connect_to_the_internet_and_try_again)
        SignInError.Cancelled, SignInError.Unavailable -> stringResource(FeaturesR.string.something_went_wrong_try_again)
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Palette.warn, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(text, style = IvType.body(13.sp), color = Palette.warn)
    }
}

/** "By continuing you agree to the Terms and Privacy Policy", with both pages on the web. */
@Composable
fun TermsNotice(links: WebLinks, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(FeaturesR.string.by_continuing_you_agree_to_insite_view_s_terms_and_privacy_p),
            style = IvType.body(13.sp),
            color = Palette.muted,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { WebPages.open(context, links.terms) }) {
                Text(stringResource(FeaturesR.string.terms), style = IvType.body(13.sp), color = Palette.accent)
            }
            TextButton(onClick = { WebPages.open(context, links.privacy) }) {
                Text(stringResource(FeaturesR.string.privacy), style = IvType.body(13.sp), color = Palette.accent)
            }
        }
    }
}

/** A provider's button title (iOS `OAuthProvider.buttonTitle`). */
@Composable
private fun providerTitle(provider: OAuthProvider): String = when (provider) {
    OAuthProvider.GOOGLE -> stringResource(FeaturesR.string.continue_with_google)
    OAuthProvider.MICROSOFT -> stringResource(FeaturesR.string.continue_with_microsoft)
    OAuthProvider.APPLE -> stringResource(R.string.app_continue_with_apple)
    else -> provider.raw.replaceFirstChar { it.uppercase() }
}

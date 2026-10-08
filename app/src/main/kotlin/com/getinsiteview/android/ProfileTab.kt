package com.getinsiteview.android

import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getinsiteview.api.Me
import com.getinsiteview.api.OrganizationRole
import com.getinsiteview.api.account.AccountError
import com.getinsiteview.core.UnitSystem
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.features.account.SignInReason
import com.getinsiteview.features.app.AppDependencies
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.features.ui.LabeledRow
import com.getinsiteview.features.ui.ListRow
import com.getinsiteview.features.ui.RowIcon
import com.getinsiteview.features.ui.SectionFooter
import com.getinsiteview.features.ui.SectionHeader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import com.getinsiteview.features.R as FeaturesR

/**
 * The Profile tab (IOS-M3-07, A-02): the account, the organizations the person belongs to and
 * their role, the unit system, a link to change the app's language, help, terms and privacy on the
 * web, sign out, and delete account behind a confirmation. Signed out, it invites the person to
 * sign in. No billing anywhere: that stays on the web (master PLAN §11).
 */
@Composable
fun ProfileTab(dependencies: AppDependencies) {
    val account = dependencies.account
    val auth by account.state.collectAsStateWithLifecycle()

    // Debug builds: the AR marking device test (iOS docs/spikes/reference-points.md).
    var showsMarkingTest by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (account.isSignedIn) account.refreshProfile()
    }

    if (showsMarkingTest) {
        androidx.activity.compose.BackHandler { showsMarkingTest = false }
        com.getinsiteview.features.diagnostics.MarkingTestView(onClose = { showsMarkingTest = false })
        return
    }

    Scaffold(
        topBar = {
            IvTopBar(title = stringResource(FeaturesR.string.profile), onBack = null, actions = {
                if (BuildConfig.DEBUG) {
                    androidx.compose.material3.IconButton(onClick = { showsMarkingTest = true }) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.CenterFocusStrong,
                            contentDescription = "AR marking test",
                        )
                    }
                }
            })
        },
        containerColor = Palette.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val me = auth.me
            if (me != null) {
                ProfileContent(dependencies, me)
            } else {
                ProfileSignedOut(dependencies)
            }
        }
    }
}

@Composable
private fun ProfileSignedOut(dependencies: AppDependencies) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        ContentUnavailable(
            title = stringResource(FeaturesR.string.your_account),
            description = stringResource(FeaturesR.string.sign_in_to_manage_your_account_organizations_and_units),
            icon = { UnavailableIcon(Icons.Outlined.AccountCircle) },
            modifier = Modifier.weight(1f),
            actions = {
                PrimaryActionButton(stringResource(FeaturesR.string.sign_in), onClick = {
                    scope.launch { dependencies.signInPrompt.requestSignIn(SignInReason.Account) }
                })
            },
        )
        AttributionFooter(Modifier.padding(16.dp))
    }
}

/** Who makes the app: the brand and the company's legal name. */
@Composable
private fun AttributionFooter(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(FeaturesR.string.insite_view_numerics_engineering),
            modifier = Modifier.fillMaxWidth(),
            style = IvType.mono(11.sp),
            color = Palette.muted,
            textAlign = TextAlign.Center,
        )
        Text(
            "Numerics Consultoria em TI LTDA · CNPJ 69.268.892/0001-02",
            modifier = Modifier.fillMaxWidth(),
            style = IvType.mono(11.sp),
            color = Palette.muted,
            textAlign = TextAlign.Center,
        )
    }
}

private enum class DeleteState { IDLE, DELETING, SOLE_OWNER, FAILED }

@Composable
private fun ProfileContent(dependencies: AppDependencies, me: Me) {
    val account = dependencies.account
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var units by remember(me.id) { mutableStateOf(me.units) }
    var confirmationSent by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var showsDeleteConfirmation by remember { mutableStateOf(false) }
    var deleteState by remember { mutableStateOf(DeleteState.IDLE) }
    var unitsMenu by remember { mutableStateOf(false) }

    fun deleteAccount() {
        scope.launch {
            deleteState = DeleteState.DELETING
            deleteState = try {
                account.deleteAccount()
                DeleteState.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: AccountError.SoleOwner) {
                DeleteState.SOLE_OWNER
            } catch (_: Exception) {
                DeleteState.FAILED
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Account
        SectionHeader(stringResource(FeaturesR.string.account))
        LabeledRow(stringResource(FeaturesR.string.name)) { Text(me.displayName, style = IvType.body(), color = Palette.muted) }
        LabeledRow(stringResource(FeaturesR.string.email)) { Text(me.email, style = IvType.body(), color = Palette.muted) }
        if (!me.emailConfirmed) {
            if (confirmationSent) {
                ListRow {
                    RowIcon(Icons.Outlined.CheckCircle, tint = Palette.muted)
                    Text(stringResource(FeaturesR.string.confirmation_email_sent_check_your_inbox), style = IvType.body(), color = Palette.muted)
                }
            } else {
                ListRow(onClick = {
                    scope.launch {
                        try {
                            account.sendEmailConfirmation()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                        confirmationSent = true
                    }
                }) {
                    RowIcon(Icons.Outlined.Email)
                    Text(stringResource(FeaturesR.string.confirm_your_email), style = IvType.body(), color = Palette.accent)
                }
            }
        }

        // Organizations
        if (me.organizations.isNotEmpty()) {
            SectionHeader(stringResource(FeaturesR.string.organizations))
            for (organization in me.organizations) {
                LabeledRow(organization.name) {
                    Text(roleName(organization.role), style = IvType.body(), color = Palette.muted)
                }
            }
            SectionFooter(stringResource(FeaturesR.string.manage_members_and_billing_on_the_web))
        }

        // Units
        SectionHeader(stringResource(FeaturesR.string.measurements))
        ListRow(onClick = { unitsMenu = true }) {
            Text(stringResource(FeaturesR.string.units), style = IvType.body(), color = Palette.ink)
            Spacer(Modifier.weight(1f))
            Box {
                Text(unitName(units), style = IvType.body(), color = Palette.accent)
                DropdownMenu(expanded = unitsMenu, onDismissRequest = { unitsMenu = false }) {
                    for (option in listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL)) {
                        DropdownMenuItem(
                            text = { Text(unitName(option)) },
                            onClick = {
                                unitsMenu = false
                                if (option != units) {
                                    units = option
                                    dependencies.unitSystem = option
                                    scope.launch {
                                        try {
                                            account.setUnits(option)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        SectionFooter(stringResource(FeaturesR.string.sizes_and_distances_use_this_system_on_this_and_your_other_d))

        // Language, help, legal
        Spacer(Modifier.size(20.dp))
        ListRow(onClick = { WebPages.openLanguageSettings(context) }) {
            RowIcon(Icons.Outlined.Language)
            Text(stringResource(FeaturesR.string.language), style = IvType.body(), color = Palette.ink)
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(16.dp))
        }
        ListRow(onClick = { WebPages.open(context, dependencies.webLinks.help) }) {
            RowIcon(Icons.AutoMirrored.Outlined.HelpOutline)
            Text(stringResource(FeaturesR.string.help), style = IvType.body(), color = Palette.ink)
        }
        ListRow(onClick = { WebPages.open(context, dependencies.webLinks.terms) }) {
            RowIcon(Icons.Outlined.Description)
            Text(stringResource(FeaturesR.string.terms), style = IvType.body(), color = Palette.ink)
        }
        ListRow(onClick = { WebPages.open(context, dependencies.webLinks.privacy) }) {
            RowIcon(Icons.Outlined.PrivacyTip)
            Text(stringResource(FeaturesR.string.privacy), style = IvType.body(), color = Palette.ink)
        }

        // Sign out and delete
        Spacer(Modifier.size(20.dp))
        ListRow(
            enabled = !signingOut,
            onClick = {
                scope.launch {
                    signingOut = true
                    try {
                        account.signOut()
                    } finally {
                        signingOut = false
                    }
                }
            },
        ) {
            Text(stringResource(FeaturesR.string.sign_out), style = IvType.body(), color = Palette.accent)
            if (signingOut) {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
            }
        }
        ListRow(enabled = deleteState != DeleteState.DELETING, onClick = { showsDeleteConfirmation = true }) {
            Text(stringResource(FeaturesR.string.delete_account), style = IvType.body(), color = Palette.accent)
            if (deleteState == DeleteState.DELETING) {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.accent, strokeWidth = 2.dp)
            }
        }

        // Version
        Spacer(Modifier.size(20.dp))
        LabeledRow(stringResource(FeaturesR.string.version)) {
            Text(BuildConfig.VERSION_NAME, style = IvType.mono(15.sp), color = Palette.muted)
        }
        AttributionFooter(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp))
    }

    if (showsDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showsDeleteConfirmation = false },
            title = { Text(stringResource(FeaturesR.string.delete_your_account)) },
            text = {
                Text(stringResource(FeaturesR.string.this_permanently_removes_your_account_and_the_buildings_you))
            },
            confirmButton = {
                TextButton(onClick = {
                    showsDeleteConfirmation = false
                    deleteAccount()
                }) {
                    Text(stringResource(FeaturesR.string.delete_account), color = Palette.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showsDeleteConfirmation = false }) {
                    Text(stringResource(FeaturesR.string.cancel))
                }
            },
        )
    }
    if (deleteState == DeleteState.SOLE_OWNER) {
        OkAlert(
            title = stringResource(FeaturesR.string.hand_over_your_company_first),
            message = stringResource(FeaturesR.string.you_re_the_only_owner_of_a_company_that_has_other_members_ma),
            onDismiss = { deleteState = DeleteState.IDLE },
        )
    }
    if (deleteState == DeleteState.FAILED) {
        OkAlert(
            title = stringResource(FeaturesR.string.couldn_t_delete_your_account),
            message = stringResource(FeaturesR.string.check_your_connection_and_try_again),
            onDismiss = { deleteState = DeleteState.IDLE },
        )
    }
}

@Composable
private fun OkAlert(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(FeaturesR.string.ok)) }
        },
    )
}

@Composable
private fun roleName(role: OrganizationRole): String = when (role) {
    OrganizationRole.OWNER -> stringResource(FeaturesR.string.owner)
    OrganizationRole.ADMIN -> stringResource(FeaturesR.string.admin)
    OrganizationRole.MEMBER -> stringResource(FeaturesR.string.member)
    else -> role.raw
}

@Composable
private fun unitName(units: UnitSystem): String = when (units) {
    UnitSystem.METRIC -> stringResource(FeaturesR.string.metric)
    UnitSystem.IMPERIAL -> stringResource(FeaturesR.string.imperial)
}

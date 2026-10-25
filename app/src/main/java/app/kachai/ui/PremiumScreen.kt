package app.kachai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kachai.BuildConfig
import app.kachai.Stats
import app.kachai.billing.Plan
import app.kachai.billing.PlanKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PremiumScreen(vm: MainViewModel, onBack: () -> Unit, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var codeDialog by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        Stats.premiumOpened()
        vm.loadPlans()
    }
    val plans = vm.plans
    var chosen by remember { mutableStateOf<Plan?>(null) }
    // Выбор тарифа — только если в RuStore заведено больше одного; иначе экран как раньше: одна подписка
    val plan = if (plans.size > 1) (chosen?.takeIf { it in plans } ?: plans.first()) else plans.firstOrNull()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Качай Плюс", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                premium -> "Плюс активен. Спасибо, что поддерживаете приложение!"
                plans.size > 1 -> "Подписка или разовая покупка — как удобнее. Подписку можно отменить в любой момент."
                else -> "Ежемесячная подписка. Отменить можно в любой момент."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Benefit(
                Icons.Rounded.HighQuality, "1080p, 2K и 4K",
                "Если ролик загружен в таком качестве. В бесплатной версии — до ${BuildConfig.FREE_MAX_HEIGHT}p",
            )
            Benefit(Icons.Rounded.AllInclusive, "Без лимита", "Сколько угодно видео в день. Бесплатно — ${BuildConfig.FREE_DAILY_LIMIT} в день")
            Benefit(Icons.Rounded.Block, "Без рекламы", "Совсем: ни баннеров, ни всплывающей рекламы")
            Benefit(Icons.Rounded.Favorite, "Поддержка разработки", "Подписка помогает развивать приложение")
        }
        if (!premium && plans.size > 1) {
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                plans.forEach { p ->
                    PlanCard(p, monthly = plans.firstOrNull { it.kind == PlanKind.MONTH }, selected = p == plan) { chosen = p }
                }
            }
        } else if (!premium) {
            Spacer(Modifier.height(28.dp))
            Text(plan?.takeIf { it.kind == PlanKind.MONTH }?.let { "${it.price} в месяц" } ?: BuildConfig.PREMIUM_PRICE, style = MaterialTheme.typography.headlineSmall)
            plan?.trialDays?.takeIf { it > 0 }?.let {
                Text(trialText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(36.dp))
        if (!premium) {
            // Оплата через RuStore; если её нет в сборке — страница оплаты по ссылке
            val canPay = vm.canBuyInApp || BuildConfig.PREMIUM_URL.isNotBlank()
            Button(
                onClick = {
                    if (vm.canBuyInApp) {
                        buying = true
                        scope.launch {
                            vm.buyPlus(plan)
                                .onSuccess { active ->
                                    if (active) {
                                        onMessage("Спасибо! Плюс активирован")
                                    } else {
                                        onMessage("Оплата прошла, Плюс включится через минуту")
                                        delay(30_000)
                                        vm.refreshPlus()
                                    }
                                }
                                .onFailure { onMessage(vm.purchaseError(it)) }
                            buying = false
                        }
                    } else {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PREMIUM_URL)))
                    }
                },
                enabled = canPay && !buying,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    when {
                        buying -> "Открываем оплату…"
                        canPay && plan != null && plans.size > 1 ->
                            if (plan.kind.days > 0) "Купить за ${plan.price}" else "Оформить подписку"
                        canPay -> "Оформить за ${plan?.takeIf { it.kind == PlanKind.MONTH }?.let { "${it.price} в месяц" } ?: BuildConfig.PREMIUM_PRICE}"
                        else -> "Оплата скоро появится"
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { codeDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("У меня есть код")
            }
        } else if (vm.canBuyInApp) {
            // Отменить подписку из приложения SDK не даёт — открываем «Профиль → Подписки» в RuStore
            OutlinedButton(
                onClick = { openRuStoreSubscriptions(context) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Отменить или изменить подписку")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Подписка оформлена в RuStore, там же она отменяется. После отмены Плюс работает до конца оплаченного месяца.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (codeDialog) {
        CodeDialog(vm, onDismiss = { codeDialog = false }, onMessage = onMessage)
    }
}

private fun trialText(days: Int) = "Первые $days ${plural(days.toLong(), "день", "дня", "дней")} — бесплатно"

/** Цена из подписи RuStore («1 190 ₽») — для сравнения тарифов; null — не разобрать. */
private fun rubles(label: String): Int? = label.filter { it.isDigit() }.toIntOrNull()

@Composable
private fun PlanCard(plan: Plan, monthly: Plan?, selected: Boolean, onClick: () -> Unit) {
    val (title, note) = when (plan.kind) {
        PlanKind.MONTH -> "Месяц" to "${plan.price} в месяц, продлевается сама"
        PlanKind.YEAR -> "Год" to "${plan.price} в год, продлевается сама"
        PlanKind.PASS_MONTH -> "30 дней" to "${plan.price} один раз, без подписки"
        PlanKind.PASS_YEAR -> "365 дней" to "${plan.price} один раз, без подписки"
    }
    // Скидку годовой подписки считаем от месячной: «−40%»
    val save = if (plan.kind == PlanKind.YEAR) {
        val m = monthly?.price?.let(::rubles)
        val y = rubles(plan.price)
        if (m != null && y != null && m > 0 && y < m * 12) "−${100 - y * 100 / (m * 12)}%" else null
    } else null
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (plan.trialDays > 0) {
                    Text(trialText(plan.trialDays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            save?.let {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Экран подписок в RuStore (rustore.ru/help/sdk/rustore-deeplinks); без RuStore — страница с инструкцией. */
private fun openRuStoreSubscriptions(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("rustore://profile/subscriptions")))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rustore.ru/help/users/purchases-and-returns/subscription-management")),
        )
    }
}

@Composable
private fun Benefit(icon: ImageVector, title: String, text: String) {
    Row {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CodeDialog(vm: MainViewModel, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Промокод или код подписки") },
        text = {
            Column {
                OutlinedTextField(
                    value = code, onValueChange = { code = it; error = null },
                    singleLine = true, isError = error != null,
                    placeholder = { Text("Вставьте код") },
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        vm.activatePremium(code)
                            .onSuccess { onMessage("Плюс активирован"); onDismiss() }
                            .onFailure { error = it.message ?: "Не получилось" }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Активировать")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

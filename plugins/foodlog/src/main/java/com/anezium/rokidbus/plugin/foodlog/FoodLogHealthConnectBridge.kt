package com.anezium.rokidbus.plugin.foodlog

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.ZoneId

/**
 * Writes a Food Log entry only after the caller has obtained an explicit user opt-in.
 *
 * The caller owns both presenting Health Connect's permission UI and persisting the opt-in;
 * this bridge deliberately has no background or automatic synchronization path.
 */
internal class FoodLogHealthConnectBridge(
    context: Context,
    private val clientFactory: (Context) -> HealthConnectClient = HealthConnectClient::getOrCreate,
) {
    private val appContext = context.applicationContext

    fun availability(): FoodLogHealthConnectAvailability = when (
        HealthConnectClient.getSdkStatus(appContext)
    ) {
        HealthConnectClient.SDK_AVAILABLE -> FoodLogHealthConnectAvailability.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            FoodLogHealthConnectAvailability.ProviderUpdateRequired
        else -> FoodLogHealthConnectAvailability.Unavailable
    }

    suspend fun hasWriteNutritionPermission(): Boolean {
        if (availability() != FoodLogHealthConnectAvailability.Available) return false
        return clientFactory(appContext)
            .permissionController
            .getGrantedPermissions()
            .contains(WRITE_NUTRITION_PERMISSION)
    }

    /**
     * Uses the documented client-record-ID upsert semantics. Entries are immutable, so their
     * consumed timestamp is a stable version; a repeated request is therefore idempotent.
     */
    suspend fun syncEntry(
        entry: FoodEntry,
        userOptedIn: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): FoodLogHealthConnectSyncResult {
        if (!userOptedIn) return FoodLogHealthConnectSyncResult.NotOptedIn
        when (val status = availability()) {
            FoodLogHealthConnectAvailability.Available -> Unit
            else -> return FoodLogHealthConnectSyncResult.Unavailable(status)
        }

        val client = try {
            clientFactory(appContext)
        } catch (exception: Exception) {
            return FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
        val hasPermission = try {
            client.permissionController.getGrantedPermissions().contains(WRITE_NUTRITION_PERMISSION)
        } catch (exception: Exception) {
            return FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
        if (!hasPermission) return FoodLogHealthConnectSyncResult.PermissionRequired

        return try {
            client.insertRecords(listOf(entry.toNutritionRecord(zoneId)))
            FoodLogHealthConnectSyncResult.Synced
        } catch (exception: SecurityException) {
            // Permissions can be revoked after the check above.
            FoodLogHealthConnectSyncResult.PermissionRequired
        } catch (exception: Exception) {
            FoodLogHealthConnectSyncResult.Failed(exception.safeMessage())
        }
    }

    companion object {
        val WRITE_NUTRITION_PERMISSION: String =
            HealthPermission.getWritePermission(NutritionRecord::class)
    }
}

internal sealed interface FoodLogHealthConnectAvailability {
    data object Available : FoodLogHealthConnectAvailability
    data object ProviderUpdateRequired : FoodLogHealthConnectAvailability
    data object Unavailable : FoodLogHealthConnectAvailability
}

internal sealed interface FoodLogHealthConnectSyncResult {
    data object Synced : FoodLogHealthConnectSyncResult
    data object NotOptedIn : FoodLogHealthConnectSyncResult
    data object PermissionRequired : FoodLogHealthConnectSyncResult
    data class Unavailable(val availability: FoodLogHealthConnectAvailability) :
        FoodLogHealthConnectSyncResult
    data class Failed(val reason: String) : FoodLogHealthConnectSyncResult
}

internal fun FoodEntry.healthConnectClientRecordId(): String = "foodlog-entry-$id"

internal fun FoodEntry.healthConnectClientRecordVersion(): Long = consumedAtMillis.coerceAtLeast(1L)

internal fun FoodEntry.toNutritionRecord(zoneId: ZoneId): NutritionRecord {
    val consumedAt = Instant.ofEpochMilli(consumedAtMillis)
    val endTime = consumedAt.plusMillis(1)
    val zoneOffset = zoneId.rules.getOffset(consumedAt)
    val nutrients = product.nutrients
    return NutritionRecord(
        startTime = consumedAt,
        startZoneOffset = zoneOffset,
        endTime = endTime,
        endZoneOffset = zoneId.rules.getOffset(endTime),
        metadata = Metadata.manualEntry(
            clientRecordId = healthConnectClientRecordId(),
            clientRecordVersion = healthConnectClientRecordVersion(),
        ),
        energy = nutrients.caloriesKcal.scaledEnergy(entry = this),
        protein = nutrients.proteinGrams.scaledMass(entry = this),
        totalCarbohydrate = nutrients.carbohydrateGrams.scaledMass(entry = this),
        totalFat = nutrients.fatGrams.scaledMass(entry = this),
        sugar = nutrients.sugarsGrams.scaledMass(entry = this),
        dietaryFiber = nutrients.fiberGrams.scaledMass(entry = this),
        // Open Food Facts' salt value is sodium chloride. Health Connect expects sodium.
        sodium = nutrients.saltGrams?.div(SALT_GRAMS_PER_SODIUM_GRAM)?.scaledMass(entry = this),
        name = product.name,
        mealType = mealTypeFor(consumedAt, zoneId),
    )
}

internal fun mealTypeFor(consumedAt: Instant, zoneId: ZoneId): Int = when (
    consumedAt.atZone(zoneId).hour
) {
    in 5..10 -> MealType.MEAL_TYPE_BREAKFAST
    in 11..15 -> MealType.MEAL_TYPE_LUNCH
    in 16..22 -> MealType.MEAL_TYPE_DINNER
    else -> MealType.MEAL_TYPE_SNACK
}

private fun Double?.scaledMass(entry: FoodEntry): Mass? =
    scaledValue(this, entry.quantityGrams)?.let(Mass::grams)

private fun Double?.scaledEnergy(entry: FoodEntry): Energy? =
    scaledValue(this, entry.quantityGrams)?.let(Energy::kilocalories)

private fun Exception.safeMessage(): String = message ?: javaClass.simpleName

private const val SALT_GRAMS_PER_SODIUM_GRAM = 2.5

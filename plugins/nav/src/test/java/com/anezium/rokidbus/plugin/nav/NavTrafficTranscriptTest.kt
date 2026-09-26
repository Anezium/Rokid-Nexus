package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins the activity traffic Navigation emits for recorded Google Maps and
 * Citymapper guidance sequences: which calls it makes (start, update with its
 * significant and urgent flags, or nothing) and the exact activity each call
 * carries. The golden file was recorded before the guidance planner moved into
 * the SDK, so this test is what proves the move changed no traffic.
 *
 * The sequences follow the parser tests' captures, with swapped street and stop
 * names.
 */
class NavTrafficTranscriptTest {
    private val mapsWalking = NavNotification(
        packageName = NavSource.GOOGLE_MAPS.packageName,
        category = "navigation",
        ongoing = true,
        title = "300 m · Prendre à droite sur Rue de Rivoli",
        subText = "Arrivée à 22:50",
        shortCriticalText = "300 m",
        progress = 27,
        progressMax = 25965,
        actions = listOf("Quitter la navigation"),
    )

    private val mapsTransit = NavNotification(
        packageName = NavSource.GOOGLE_MAPS.packageName,
        category = "navigation",
        ongoing = true,
        title = "Marchez 3 min (250 m)",
        text = "Châtelet · Départ à 17:36",
        subText = "Arrivée à 18:51",
        shortCriticalText = "3 min",
        progress = 0,
        progressMax = 100,
        actions = listOf("Arrêter le trajet"),
    )

    private fun citymapper(title: String?, subtitle: String? = null, prediction: String? = null) = NavNotification(
        packageName = NavSource.CITYMAPPER.packageName,
        channelId = "trip-progress",
        ongoing = true,
        actions = listOf("Terminer", "Préc.", "Suivant"),
        viewTexts = buildMap {
            title?.let { put("notification_title", it) }
            subtitle?.let { put("notification_subtitle", it) }
            prediction?.let { put("notification_prediction", it) }
            put("notification_eta", "Arrivée : 18:23 (83 min)")
        },
    )

    private fun mapsDriveTranscript(): List<NavGuidance?> = listOf(
        mapsWalking.copy(title = "Aller vers Rue de Rivoli/Rue du Louvre", shortCriticalText = ""),
        mapsWalking,
        mapsWalking.copy(title = "120 m · Prendre à droite sur Rue de Rivoli", shortCriticalText = "120 m"),
        mapsWalking.copy(title = "120 m · Prendre à droite sur Rue de Rivoli", shortCriticalText = "120 m"),
        mapsWalking.copy(title = "30 m · Prendre à droite sur Rue de Rivoli", shortCriticalText = "30 m"),
        mapsWalking.copy(title = "20 m · Prendre à droite sur Rue de Rivoli", shortCriticalText = "20 m"),
        mapsWalking.copy(title = "450 m · Continuez sur Bd Saint-Michel", shortCriticalText = "450 m"),
        mapsWalking.copy(title = "40 m · Tournez légèrement à gauche sur Rue X", shortCriticalText = "40 m"),
        mapsWalking.copy(title = "200 m · Continue to your destination", shortCriticalText = "200 m"),
        mapsWalking.copy(title = "Vous êtes arrivé", shortCriticalText = null),
    ).map { GoogleMapsParser.parse(it) }

    private fun mapsTransitTranscript(): List<NavGuidance?> = listOf(
        mapsTransit,
        mapsTransit.copy(title = "Marchez 2 min (150 m)", shortCriticalText = "2 min"),
        mapsTransit.copy(title = "Prenez la ligne 2345", text = "Porte d'Orléans · Départ à 17:48", shortCriticalText = "17:48", progressMax = 0),
        mapsTransit.copy(title = "Descendez dans 3 arrêts", text = "Luxembourg · Bus 38", shortCriticalText = null),
        mapsTransit.copy(title = "Descendez dans 2 arrêts", text = "Luxembourg · Bus 38", shortCriticalText = null),
        mapsTransit.copy(title = "Descendez au prochain arrêt", text = "Luxembourg · Bus 38", shortCriticalText = null),
        mapsTransit.copy(title = "Correspondance", text = "Gare du Nord", shortCriticalText = "6 min"),
    ).map { GoogleMapsParser.parse(it) }

    private fun citymapperTranscript(): List<NavGuidance?> {
        val parser = CitymapperParser()
        return listOf(
            citymapper("Marcher vers l'arrêt de bus", "Châtelet", "(à 4 min)"),
            citymapper("Marcher vers l'arrêt de bus", "Châtelet", "(à 2 min)"),
            citymapper("Attendre 38 ou 85", "4, 11, 11 min"),
            citymapper("Attendre 38 ou 85", "0, 11, 11 min"),
            citymapper("3 arrêts jusqu'à", "Luxembourg"),
            citymapper("2 arrêts jusqu'à", "Luxembourg"),
            citymapper("1 arrêt jusqu'à", "Luxembourg"),
            citymapper("Descendre au prochain arrêt", "Luxembourg"),
            citymapper("17:34 ZECO Melun (à l'heure) ", "voie 2"),
            citymapper("6 arrêts jusqu'à", "Gare du Nord"),
            citymapper("Prenez la sortie 3"),
        ).map { parser.parse(it) }
    }

    private fun traffic(transcript: List<NavGuidance?>): List<String> {
        val planner = NavActivityPlanner()
        return transcript.map { guidance ->
            // An unreadable notification ends the route in NavRuntime; it never reaches the planner.
            if (guidance == null) return@map "UNREADABLE"
            when (val plan = planner.plan(guidance)) {
                is NavPlan.Start -> "START ${plan.guidance.toActivity()}"
                is NavPlan.Update ->
                    "UPDATE significant=${plan.significant} urgent=${plan.urgent} ${plan.guidance.toActivity()}"
                NavPlan.Unchanged -> "UNCHANGED"
            }
        }
    }

    private fun transcriptText(): String = buildString {
        listOf(
            "google-maps-drive" to mapsDriveTranscript(),
            "google-maps-transit" to mapsTransitTranscript(),
            "citymapper" to citymapperTranscript(),
        ).forEach { (name, transcript) ->
            append("# ").append(name).append('\n')
            traffic(transcript).forEach { append(it).append('\n') }
        }
    }

    @Test
    fun `recorded guidance emits the pinned activity traffic`() {
        val actual = transcriptText()
        val golden = javaClass.classLoader?.getResource(GOLDEN)?.readText(Charsets.UTF_8)
        if (golden == null) {
            File("build", GOLDEN).apply { parentFile?.mkdirs() }.writeText(actual, Charsets.UTF_8)
            error("No $GOLDEN in test resources; recorded the current traffic to build/$GOLDEN")
        }
        assertEquals(golden.replace("\r\n", "\n"), actual)
    }

    private companion object {
        const val GOLDEN = "nav-traffic-golden.txt"
    }
}

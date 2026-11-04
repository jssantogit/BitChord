package com.music.bitchord.data.listening

import android.app.Application
import androidx.room.Room
import com.music.bitchord.data.catalog.CanonicalCatalogDatabase
import com.music.bitchord.data.catalog.CanonicalLibraryAvailabilityRepository
import com.music.bitchord.data.catalog.CanonicalLibraryProviderConfigurationReader
import com.music.bitchord.data.catalog.RoomCanonicalLibraryAvailabilityStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * CI HOST benchmark, not a physical Android performance certification.
 *
 * Uses 50,123 REAL SQLite/Room Recording rows, the production batch reader
 * and the production V2 evaluator. Setup/insertion is NOT included in timing.
 * Wall time and heap observations are deliberately informational: shared CI
 * runners have variable CPU/GC and must never fail on an arbitrary ms budget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartPlaylistCiBenchmarkTest {
    private companion object {
        const val RECORD_COUNT = 50_123
        const val MAX_BATCH = 400
        const val LIMIT = 500
        const val MEASUREMENTS = 3
    }

    private data class Sample(
        val roomReadMs: Long,
        val evaluationMs: Long,
        val heapDeltaBytes: Long,
    )

    @Test fun fiftyThousandRealRoomRowsBenchmarkWithoutTruncationOrNPlusOne() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Room.inMemoryDatabaseBuilder(context, CanonicalCatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        val listening = Room.inMemoryDatabaseBuilder(context, ListeningEventDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            // One setup transaction; the benchmark measures only reads/evaluation.
            val db = catalog.openHelper.writableDatabase
            db.beginTransaction()
            try {
                val insertAsset = db.compileStatement(
                    "INSERT INTO catalog_media_asset " +
                        "(id,recordingId,provider,sourceKey,uri,path,dateAddedSeconds,dateModifiedSeconds,artworkUri) " +
                        "VALUES (?,?,?,?,?,?,NULL,NULL,NULL)",
                )
                val insertTags = db.compileStatement(
                    "INSERT INTO catalog_media_asset_tags " +
                        "(mediaAssetId,genresJson,moodsJson,bpmsJson,stylesJson,composersJson,localSourceFingerprint,localParsedFingerprint) " +
                        "VALUES (?,?,NULL,NULL,NULL,?,NULL,NULL)",
                )
                val insert = db.compileStatement(
                    "INSERT INTO catalog_recording (id, title, artistDisplay, durationMs) " +
                        "VALUES (?, ?, ?, ?)",
                )
                for (i in 0 until RECORD_COUNT) {
                    insert.clearBindings()
                    insert.bindString(1, "rec:" + i.toString().padStart(6, '0'))
                    insert.bindString(
                        2, (if (i % 13 == 0) "Excluded " else "Track ") +
                            i.toString().padStart(6, '0'),
                    )
                    insert.bindString(3, "Benchmark artist")
                    insert.bindLong(4, if (i % 17 == 0) 120_000L else 210_000L)
                    insert.executeInsert()
                    val id = "rec:" + i.toString().padStart(6, '0')
                    val assetId = "asset:" + i.toString().padStart(6, '0')
                    insertAsset.clearBindings()
                    insertAsset.bindString(1, assetId)
                    insertAsset.bindString(2, id)
                    insertAsset.bindString(3, "local-mediastore")
                    insertAsset.bindString(4, "local:" + i)
                    insertAsset.bindString(5, "content://media/external/audio/media/" + i)
                    insertAsset.bindString(6, "Music/Batch/" + i % 20 + "/" + i + ".flac")
                    insertAsset.executeInsert()
                    insertTags.clearBindings()
                    insertTags.bindString(1, assetId)
                    insertTags.bindString(2, if (i % 7 == 0) "[\"Jazz\"]" else "[]")
                    insertTags.bindString(3, if (i % 3 == 0) "[\"Alice\"]" else "[]")
                    insertTags.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            val ids = (0 until RECORD_COUNT).map { "rec:" + it.toString().padStart(6, '0') }
            val batches = ids.chunked(MAX_BATCH)
            assertEquals(126, batches.size)
            assertEquals(RECORD_COUNT, batches.sumOf { it.size })
            assertTrue(batches.all { it.size in 1..MAX_BATCH })
            val expected = (0 until RECORD_COUNT).asSequence()
                .filter { it % 13 != 0 && it % 17 != 0 }
                .take(LIMIT)
                .map { "rec:" + it.toString().padStart(6, '0') }
                .toList()

            val rule = SmartPlaylistRule.Group(
                SmartPlaylistRuleJoin.ALL,
                listOf(
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.TITLE,
                        SmartPlaylistRuleOperator.CONTAINS,
                        textValue = "Track",
                    ),
                    SmartPlaylistRule.Comparison(
                        SmartPlaylistRuleField.DURATION_MS,
                        SmartPlaylistRuleOperator.GREATER_OR_EQUAL,
                        value = 180_000.0,
                    ),
                ),
            )
            val query = SmartPlaylistV2Query(
                rules = rule, sort = SmartPlaylistV2Sort.TITLE_ASC, limit = LIMIT,
            )
            assertEquals(
                setOf(SmartPlaylistRuleField.TITLE, SmartPlaylistRuleField.DURATION_MS),
                query.requiredEvidenceFields(),
            )
            val reader = RoomSmartPlaylistBatchEvidenceReader(
                catalog.catalogDao(),
                listening.listeningEventDao(),
                listening.recordingRatingDao(),
            )
            val engine = SmartPlaylistV2RuleEngine()

            fun heapUsed(): Long {
                val runtime = Runtime.getRuntime()
                return runtime.totalMemory() - runtime.freeMemory()
            }

            suspend fun runPass(): Sample {
                val before = heapUsed()
                val readStart = System.nanoTime()
                val rows = batches.flatMap { batch ->
                    reader.read(batch, query.requiredEvidenceFields())
                }
                val readEnd = System.nanoTime()
                assertEquals(RECORD_COUNT, rows.size)
                val result = engine.preview(rows, query)
                val evalEnd = System.nanoTime()
                assertEquals(expected, result.map { it.recordingId })
                assertEquals(LIMIT, result.size)
                assertEquals(LIMIT, result.map { it.recordingId }.distinct().size)
                return Sample(
                    roomReadMs = (readEnd - readStart) / 1_000_000L,
                    evaluationMs = (evalEnd - readEnd) / 1_000_000L,
                    heapDeltaBytes = heapUsed() - before,
                )
            }

            // The same 50,123 physical MediaAsset and tag rows also exercise
            // the source-neutral Composer + Folder path query end-to-end.
            // Each provider account stays isolated: no global Recording tags.
            val scopedReader = CanonicalLibraryAvailabilityRepository(
                RoomCanonicalLibraryAvailabilityStore(catalog.catalogDao()),
                CanonicalLibraryProviderConfigurationReader { setOf("local-mediastore") },
            )
            val scopedRule = SmartPlaylistRule.Group(SmartPlaylistRuleJoin.ALL, listOf(
                SmartPlaylistRule.Comparison(SmartPlaylistRuleField.COMPOSER,
                    SmartPlaylistRuleOperator.EQUAL, textValue = "Alice"),
                SmartPlaylistRule.Comparison(SmartPlaylistRuleField.FOLDER_PATH,
                    SmartPlaylistRuleOperator.EQUAL, textValue = "Music/Batch/1"),
            ))
            val scopedQuery = SmartPlaylistV2Query(scopedRule,
                sort = SmartPlaylistV2Sort.RECORDING_ID_ASC, limit = LIMIT)
            val scopedStart = System.nanoTime()
            val tags = scopedReader.availableRecordingTags(ids)
            val scopedReadMs = (System.nanoTime() - scopedStart) / 1_000_000L
            assertEquals(RECORD_COUNT, tags.size)
            val taggedCandidates = tags.map { tag -> RuleBasedRecordingCandidate(
                recordingId = tag.recordingId, explicitRating = null,
                audibleCompletions = 0L, lastAudibleAtEpochMs = null,
                durationMs = null, available = true,
                composers = tag.composers,
                composerEvidenceUnknown = tag.composerEvidenceUnknown,
                filePaths = tag.filePaths,
                filePathEvidenceUnknown = tag.filePathEvidenceUnknown,
                folderPaths = tag.folderPaths,
                folderPathEvidenceUnknown = tag.folderPathEvidenceUnknown,
            ) }
            val scopedEvalStart = System.nanoTime()
            val scopedResult = engine.preview(taggedCandidates, scopedQuery)
            val scopedEvalMs = (System.nanoTime() - scopedEvalStart) / 1_000_000L
            val scopedExpected = (0 until RECORD_COUNT).asSequence()
                .filter { it % 3 == 0 && it % 20 == 1 }.take(LIMIT)
                .map { "rec:" + it.toString().padStart(6, '0') }.toList()
            assertEquals(scopedExpected, scopedResult.map { it.recordingId })
            assertEquals(LIMIT, scopedResult.size)
            assertTrue(scopedReadMs >= 0 && scopedEvalMs >= 0)

            runPass() // JIT/Room warm-up: not included in reported samples.
            val samples = (0 until MEASUREMENTS).map { runPass() }
            fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
            val readMs = samples.map { it.roomReadMs }
            val evaluateMs = samples.map { it.evaluationMs }
            val heapDelta = samples.map { it.heapDeltaBytes }
            val medianRead = median(readMs)
            val medianEvaluate = median(evaluateMs)
            val medianHeapDelta = median(heapDelta)
            assertTrue(samples.size == MEASUREMENTS)
            assertTrue(samples.all { it.roomReadMs >= 0 && it.evaluationMs >= 0 })

            val reportDir = File("build/reports/smart-playlist-benchmark")
            check(reportDir.exists() || reportDir.mkdirs())
            val json = """
                {
                  "environment": "CI JVM Robolectric with real in-memory Room",
                  "device_benchmark": false,
                  "records": $RECORD_COUNT,
                  "max_batch_size": $MAX_BATCH,
                  "batch_count_per_pass": ${batches.size},
                  "matched": $LIMIT,
                  "warmup_passes": 1,
                  "measured_passes": $MEASUREMENTS,
                  "room_read_ms": ${readMs.joinToString(prefix = "[", postfix = "]")},
                  "evaluation_ms": ${evaluateMs.joinToString(prefix = "[", postfix = "]")},
                  "heap_delta_bytes": ${heapDelta.joinToString(prefix = "[", postfix = "]")},
                  "median_room_read_ms": $medianRead,
                  "median_evaluation_ms": $medianEvaluate,
                  "median_heap_delta_bytes": $medianHeapDelta,
                  "metadata_scoped_filter": "COMPOSER = Alice AND FOLDER_PATH = Music/Batch/1",
                  "metadata_scoped_records": $RECORD_COUNT,
                  "metadata_scoped_matched": ${scopedResult.size},
                  "metadata_scoped_room_read_ms": $scopedReadMs,
                  "metadata_scoped_v2_evaluation_ms": $scopedEvalMs
                }
            """.trimIndent()
            File(reportDir, "metrics.json").writeText(json + "\n")
            File(reportDir, "summary.md").writeText(
                "### Smart playlists V2 · benchmark CI\n" +
                    "- Ambiente: JVM/Robolectric + 50.123 registros SQLite/Room (não é aparelho físico)\n" +
                    "- Batches por medição: ${batches.size}, máximo $MAX_BATCH IDs\n" +
                    "- Resultados validados: $LIMIT músicas, IDs determinísticos, sem truncamento\n" +
                    "- Mediana leitura Room: ${medianRead} ms (3 medições, após aquecimento)\n" +
                    "- Metadados locais (50.123 assets): compositor+pasta, $LIMIT IDs exatos\n" +
                    "- Metadados: leitura Room ${scopedReadMs} ms; avaliação V2 ${scopedEvalMs} ms (1 amostra informativa)\n" +
                    "- Mediana motor V2: ${medianEvaluate} ms\n" +
                    "- Variação mediana observada de heap: ${medianHeapDelta} bytes (não é pico de memória)\n" +
                    "- Tempo e heap **informativos**, sem limites rígidos devido à variabilidade dos runners\n" +
                    "- Validação final de latência, RAM, UI e Media3 permanece no Galaxy A54\n",
            )
            println("SMART_PLAYLIST_CI_BENCHMARK $json")
        } finally {
            catalog.close()
            listening.close()
        }
    }
}

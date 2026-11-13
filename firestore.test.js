const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validPayload(uid) {
  return {
    userId: uid,
    sessionId: "sess_001",
    deviceModel: "Xiaomi 23049PCD8G",
    socBadge: "Snapdragon 8 Gen 2 (Hexagon v73 HTP)",
    executionProvider: "AUTO_NPU_GPU",
    promptTokens: 320,
    generatedTokens: 96,
    batchTokenizeMs: 4.5,
    gpuPrefetchMs: 2.1,
    prefillTokPerSec: 128.4,
    decodeTokPerSec: 14.8,
    avgNpuStepMs: 67.5,
    peakNpuUtilPercent: 86,
    thermalStatusLabel: "Nominal",
    isThermalThrottling: false,
    nativeHeapMb: 412,
    availRamMb: 3820,
    inspectDurationMs: 190,
    cloneDurationMs: 310,
    compactedTurns: 2,
    status: "CLONED_DOCX_OK",
    createdAt: new Date(Date.now() - 1000),
    updatedAt: new Date(Date.now() - 1000),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write telemetry_runs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("telemetry_runs").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("telemetry_runs")
      .doc("run_1")
      .set(validPayload(ALICE_UID))
  );
});

test("Authenticated owner: can create, get, and list own telemetry_runs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("telemetry_runs")
    .doc("run_valid_1");

  await assertSucceeds(docRef.set(validPayload(ALICE_UID)));
  await assertSucceeds(docRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("telemetry_runs").get()
  );
});

test("Cross-user isolation: Bob cannot read or write Alice's telemetry_runs", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx
      .firestore()
      .collection("users")
      .doc(ALICE_UID)
      .collection("telemetry_runs")
      .doc("run_alice")
      .set(validPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("telemetry_runs")
      .doc("run_alice")
      .get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("telemetry_runs").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("telemetry_runs")
      .doc("run_spoof")
      .set(validPayload(ALICE_UID))
  );
});

test("Shadow field & value poisoning: rejects extra fields or out-of-range NPU percent", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ghostPayload = {
    ...validPayload(ALICE_UID),
    isAdmin: true,
  };
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("telemetry_runs")
      .doc("run_ghost")
      .set(ghostPayload)
  );

  const invalidPctPayload = {
    ...validPayload(ALICE_UID),
    peakNpuUtilPercent: 150,
  };
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("telemetry_runs")
      .doc("run_bad_pct")
      .set(invalidPctPayload)
  );
});

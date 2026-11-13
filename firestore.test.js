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
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const pastTs = () => new Date(Date.now() - 2000);

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

test("Unauthenticated user: cannot read posts, users, content_registry, or messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("posts").where("visibility", "==", "public").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("content_registry").where("visibility", "==", "public").get());
  await assertFails(
    unauthDb.collection("encrypted_messages").where("participantIds", "array-contains", ALICE_UID).get()
  );
});

test("UserAccount: owner can create and read own account, cross-user read/write blocked, ghost field rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const validAccount = {
    userId: ALICE_UID,
    displayName: "Alice Yekta",
    handle: "alice_y",
    bio: "Gizlilik savunucusu",
    planTier: "ucretsiz",
    publicKeyFingerprint: "AB12CD34EF567890",
    dashboardWidgets: ["uniqueness_rate", "similarity_chart"],
    createdAt: pastTs(),
    updatedAt: pastTs(),
  };

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validAccount));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());

  // Cross-user read/write must fail
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({ displayName: "Hacked", updatedAt: pastTs() })
  );

  // Ghost field injection (Shadow Update) must fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastTs(),
    })
  );
});

test("SocialPost & 1x ContentRegistry: owner creates post + unique hash; duplicate hash rejected; public query aligned", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const hashId = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  const validPost = {
    authorId: ALICE_UID,
    authorName: "Alice Yekta",
    authorHandle: "alice_y",
    format: "text",
    caption: "Yekta ağında ilk özgün paylaşımım.",
    mediaDataUri: "",
    tags: ["ozgun", "yekta"],
    contentHash: hashId,
    perceptualHash: "a1b2c3d4e5f60708",
    uniquenessScore: 98,
    semanticSummary: "İlk özgün paylaşım duyurusu",
    visibility: "public",
    likeCount: 0,
    repostCount: 0,
    replyCount: 0,
    createdAt: pastTs(),
    updatedAt: pastTs(),
  };

  await assertSucceeds(aliceDb.collection("posts").doc("post_1").set(validPost));

  const registryEntry = {
    ownerId: ALICE_UID,
    postId: "post_1",
    contentHash: hashId,
    perceptualHash: "a1b2c3d4e5f60708",
    format: "text",
    semanticSummary: "İlk özgün paylaşım duyurusu",
    textPreview: "Yekta ağında ilk özgün paylaşımım.",
    visibility: "public",
    createdAt: pastTs(),
  };
  await assertSucceeds(aliceDb.collection("content_registry").doc(hashId).set(registryEntry));

  // Bob cannot overwrite/duplicate the same 1x hash
  await assertFails(
    bobDb.collection("content_registry").doc(hashId).set({
      ...registryEntry,
      ownerId: BOB_UID,
      postId: "post_2",
    })
  );

  // Aligned public query succeeds, unfiltered query fails
  await assertSucceeds(bobDb.collection("posts").where("visibility", "==", "public").get());
  await assertFails(bobDb.collection("posts").get());

  // Bob can increment likeCount by +1, cannot arbitrarily jump by +10 or edit caption
  await assertSucceeds(
    bobDb.collection("posts").doc("post_1").update({ likeCount: 1, updatedAt: pastTs() })
  );
  await assertFails(
    bobDb.collection("posts").doc("post_1").update({ likeCount: 10, updatedAt: pastTs() })
  );
  await assertFails(
    bobDb.collection("posts").doc("post_1").update({ caption: "Değiştirildi", updatedAt: pastTs() })
  );
});

test("EncryptedMessage: participant isolation enforced on get and list", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  const msg = {
    senderId: ALICE_UID,
    recipientId: BOB_UID,
    participantIds: [ALICE_UID, BOB_UID],
    senderName: "Alice",
    ciphertext: "ZW5jcnlwdGVkX3BheWxvYWRfYmFzZTY0",
    nonce: "bm9uY2UxMjM0NTY=",
    ephemeralPubKey: "cHVibGljX2tleV9iNjRfZW5jb2RlZA==",
    onionHopsCount: 3,
    transportMode: "encrypted_relay",
    createdAt: pastTs(),
  };

  await assertSucceeds(aliceDb.collection("encrypted_messages").doc("msg_1").set(msg));

  // Bob (participant) can read and list with array-contains
  await assertSucceeds(bobDb.collection("encrypted_messages").doc("msg_1").get());
  await assertSucceeds(
    bobDb.collection("encrypted_messages").where("participantIds", "array-contains", BOB_UID).get()
  );

  // Charlie (non-participant) cannot read or query
  await assertFails(charlieDb.collection("encrypted_messages").doc("msg_1").get());
  await assertFails(charlieDb.collection("encrypted_messages").get());
});

test("AnalyticsReport: strictly isolated per user", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const report = {
    userId: ALICE_UID,
    title: "Haftalık Özgünlük Raporu",
    totalChecked: 12,
    uniqueApproved: 10,
    duplicatesBlocked: 2,
    avgSimilarityScore: 18,
    recommendationsSummary: "Görsel ve metin paylaşımları yüksek özgünlük oranına sahip.",
    createdAt: pastTs(),
  };

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("analytics_reports").doc("rep_1").set(report)
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("analytics_reports").get()
  );

  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("analytics_reports").doc("rep_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("analytics_reports").get()
  );
});

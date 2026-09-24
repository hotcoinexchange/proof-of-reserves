# Hotcoin Merkle Sum Tree Verification

This project verifies offline whether a user's assets are correctly included in the published Hotcoin Proof of Reserves Merkle Sum Tree root. Verification performs only local hashing and balance calculations and does not access the network.

## Requirements

- JDK 17
- Maven 3.8+

## Files Required for Verification

Verification requires two JSON files:

- `proof.json`: The personal proof obtained by an authenticated user from the platform. It contains anonymous account information, asset balances, and all sibling nodes from the user's leaf to the root.
- `anchor.json`: The root information published by the platform for a snapshot through an independent, trusted channel. It contains the root hash, asset precision, actual leaf count, and total user assets.

The repository includes [sample-proof.json](examples/sample-proof.json) and [sample-anchor.json](examples/sample-anchor.json), which can be used directly for a self-check.

The root information must not be derived from the `proof.json` being verified. You should also not trust a root value solely because it was returned together with the personal proof. Before verification, confirm that `anchor.json` was obtained from an official, trusted publication channel operated by the platform.

## Running the Verification

First, build the verifier and copy its runtime dependencies:

```shell
mvn clean package dependency:copy-dependencies
```

Linux/macOS：

```shell
java --class-path "target/merkle-sum-tree-1.0.0-SNAPSHOT.jar:target/dependency/*" \
  MerkleExample proof.json anchor.json
```

Windows：

```shell
java --class-path "target/merkle-sum-tree-1.0.0-SNAPSHOT.jar;target/dependency/*" MerkleExample proof.json anchor.json
```

When no file arguments are provided, the verifier runs a self-check using the samples included in the repository:

```shell
java --class-path "target/merkle-sum-tree-1.0.0-SNAPSHOT.jar:target/dependency/*" MerkleExample
```

Successful verification prints:

```text
valid=true
```

If the proof format is invalid, any field has been modified, the path is incomplete, the asset totals are inconsistent, or the root hash does not match, the verifier prints `valid=false` and exits with an exception.

## JSON Contents

`proof.json` contains:

- `protocol`: The identifier of the hashing protocol.
- `assets`: The assets and their ledger precision. Their order is part of the hashing protocol.
- `self`: The current user's anonymous `accountId`, random `nonce`, balances, leaf hash, and leaf position.
- `path`: All sibling nodes ordered from the leaf toward the root. `position` indicates whether each sibling is on the left or right of the current node.

`anchor.json` contains:

- `protocol`: The identifier of the hashing protocol.
- `assets`: The assets and precision, which must exactly match the proof.
- `snapshotId`: The asset snapshot identifier.
- `root`: The officially published Merkle Sum Tree root hash.
- `leafCount`: The actual number of user leaves in the snapshot.
- `userTotals`: The total user assets for each asset recorded at the tree root.

The personal proof does not contain the user's real UID. The verifier recalculates the user's leaf hash, computes each parent node along the `path`, and verifies the asset sum at every level. The final calculated root hash and asset totals must exactly match those in `anchor.json`.

## Using the Java API

```java
String proofJson = Files.readString(Path.of("proof.json"));
String anchorJson = Files.readString(Path.of("anchor.json"));
boolean valid = MerkleProofVerifier.verify(proofJson, anchorJson);
```

The verification entry point returns `false` for both malformed input and verification failures.

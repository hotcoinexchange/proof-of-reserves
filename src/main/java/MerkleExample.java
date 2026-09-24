import com.hotcoin.core.MerkleProofVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 使用平台公布的个人证明和独立可信根信息进行离线验证。 */
public class MerkleExample {
    public static void main(String[] args) throws IOException {
        Path proofFile;
        Path anchorFile;
        if (args.length == 0) {
            proofFile = Path.of("examples", "sample-proof.json");
            anchorFile = Path.of("examples", "sample-anchor.json");
        } else if (args.length == 2) {
            proofFile = Path.of(args[0]);
            anchorFile = Path.of(args[1]);
        } else {
            throw new IllegalArgumentException(
                    "Usage: MerkleExample [proof.json anchor.json]");
        }

        String proofJson = Files.readString(proofFile);
        String anchorJson = Files.readString(anchorFile);
        boolean valid = MerkleProofVerifier.verify(proofJson, anchorJson);
        System.out.println("valid=" + valid);
        if (!valid) {
            throw new IllegalStateException("Merkle proof verification failed");
        }
    }
}

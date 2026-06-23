package com.ruoyi.sensor.service;

import com.ruoyi.common.utils.CollectorSecretCrypto;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CollectorSecretCryptoTest
{
    @Test
    void encryptsSecretsAndProducesStableHmac()
    {
        String master = "collector-master-key-at-least-32-bytes";
        String secret = CollectorSecretCrypto.generateSecret();
        String encrypted = CollectorSecretCrypto.encrypt(secret, master);

        assertNotEquals(secret, encrypted);
        assertEquals(secret, CollectorSecretCrypto.decrypt(encrypted, master));
        assertEquals(CollectorSecretCrypto.hmacHex(secret, "canonical"),
            CollectorSecretCrypto.hmacHex(secret, "canonical"));
    }
}

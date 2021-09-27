package org.joget.marketplace;

import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import okhttp3.HttpUrl;
import org.joget.commons.util.LogUtil;
import org.joget.commons.util.SecurityUtil;
import org.xrpl.xrpl4j.client.JsonRpcClientErrorException;
import org.xrpl.xrpl4j.client.XrplClient;
import org.xrpl.xrpl4j.keypairs.KeyPair;
import org.xrpl.xrpl4j.model.client.common.LedgerIndex;
import org.xrpl.xrpl4j.model.client.fees.FeeResult;
import org.xrpl.xrpl4j.model.client.ledger.LedgerRequestParams;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;
import org.xrpl.xrpl4j.wallet.DefaultWalletFactory;
import org.xrpl.xrpl4j.wallet.Wallet;
import org.xrpl.xrpl4j.wallet.WalletFactory;

public class XrplUtil {
    
    public static final String TESTNET_URL = "https://s.altnet.rippletest.net:51234/";
    public static final String DEVNET_URL = "https://s.devnet.rippletest.net:51234/";
    public static final String GENERAL_MAINNET_URL = "https://s1.ripple.com:51234/";
    public static final String FULL_HISTORY_MAINNET_URL = "https://s2.ripple.com:51234/";
    
    public static final String TESTNET_FAUCET_URL = "https://faucet.altnet.rippletest.net";
    public static final String DEVNET_FAUCET_URL = "https://faucet.devnet.rippletest.net";
    
    public static final String TESTNET_TX_EXPLORER_URL = "https://testnet.xrpl.org/transactions/";
    public static final String DEVNET_TX_EXPLORER_URL = "https://devnet.xrpl.org/transactions/";
    public static final String MAINNET_TX_EXPLORER_URL = "https://livenet.xrpl.org/transactions/";
    
    //Average tx processing time is about 3-7 seconds
    public static final int TX_PROCESSING_WAIT_TIME_MILLISECONDS = 4000;
    
    public static XrplClient getXrplClient(String rippledServer, String rippledUrl) {
        
        XrplClient xrplClient = null;
        
        try {
            HttpUrl url;

            //Default to "testnet" in case of no selection for rippledServer
            switch (rippledServer) {
                case "generalMainnet":
                    url = HttpUrl.get(GENERAL_MAINNET_URL);
                    break;
                case "fullHistoryMainnet":
                    url = HttpUrl.get(FULL_HISTORY_MAINNET_URL);
                    break;
                case "devnet":
                    url = HttpUrl.get(DEVNET_URL);
                    break;
                case "custom":
                    url = HttpUrl.get(rippledUrl);
                    break;
                default:
                    url = HttpUrl.get(TESTNET_URL);
                    break;
            }

            xrplClient = new XrplClient(url);
        } catch (Exception ex) {
            LogUtil.error(XrplUtil.class.getName(), ex, "");
        }
        
        return xrplClient;
    }
    
    public static String getTransactionExplorerUrl(String rippledServer, String transactionHash) {
        String transactionUrl;
        
        switch (rippledServer) {
            case "testnet":
                transactionUrl = TESTNET_TX_EXPLORER_URL;
                break;
            case "devnet":
                transactionUrl = DEVNET_TX_EXPLORER_URL;
                break;
            default:
                transactionUrl = MAINNET_TX_EXPLORER_URL;
                break;
        }
        transactionUrl += transactionHash;
        
        return transactionUrl;
    }
    
    public static Wallet getWalletFromKeyPair(KeyPair kp, boolean isTest) {
        WalletFactory walletFactory = DefaultWalletFactory.getInstance();
        final Wallet wallet = walletFactory.fromKeyPair(kp, isTest);
        
        return wallet;
    }
    
    public static Wallet getWalletFromSeed(String seed, boolean isTest) {
        WalletFactory walletFactory = DefaultWalletFactory.getInstance();
        final Wallet wallet = walletFactory.fromSeed(seed, isTest);
        
        return wallet;
    }
    
    //Feel free to implement more secure encryption algo
    public static String encrypt(String content) {
        content = SecurityUtil.encrypt(content);
        
        return content;
    }
    
    //Feel free to implement more secure encryption algo, and decrypt accordingly
    public static String decrypt(String content) {
        content = SecurityUtil.decrypt(content);
        
        return content;
    }
    
    public static XrpCurrencyAmount getCurrentOpenLedgerFeeInDrops(XrplClient client) throws JsonRpcClientErrorException {
        final FeeResult feeResult = client.fee();
        final XrpCurrencyAmount openLedgerFee = feeResult.drops().openLedgerFee();
        
        return openLedgerFee;
    }
    
    public static String getCurrentOpenLedgerFeeInXrp(XrplClient client) throws JsonRpcClientErrorException {
        final String openLedgerFeeInXrp = getCurrentOpenLedgerFeeInDrops(client).toXrp().toString();
        
        return openLedgerFeeInXrp;
    }
    
    public static LedgerIndex getLatestValidatedLedgerIndex(XrplClient client) throws JsonRpcClientErrorException {
        final LedgerIndex validatedLedger = client.ledger(LedgerRequestParams.builder().ledgerIndex(LedgerIndex.VALIDATED).build())
            .ledgerIndex()
            .orElseThrow(() -> new RuntimeException("LedgerIndex not available..."));
        
        return validatedLedger;
    }
    
    public static UnsignedInteger getLastLedgerSequence(LedgerIndex validatedLedger) {
        final UnsignedInteger lastLedgerSequence = UnsignedInteger.valueOf(validatedLedger.plus(UnsignedLong.valueOf(4)).unsignedLongValue().intValue());
        
        return lastLedgerSequence;
    }
}

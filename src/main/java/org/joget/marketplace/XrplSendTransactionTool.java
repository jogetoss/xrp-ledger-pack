package org.joget.marketplace;

import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.math.BigDecimal;
import java.util.Map;
import org.joget.apps.app.service.AppUtil;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;
import org.springframework.context.ApplicationContext;
import org.xrpl.xrpl4j.client.JsonRpcClientErrorException;
import org.xrpl.xrpl4j.client.XrplClient;
import org.xrpl.xrpl4j.crypto.KeyMetadata;
import org.xrpl.xrpl4j.crypto.PrivateKey;
import org.xrpl.xrpl4j.crypto.signing.SignedTransaction;
import org.xrpl.xrpl4j.crypto.signing.SingleKeySignatureService;
import org.xrpl.xrpl4j.crypto.signing.SignatureService;
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoRequestParams;
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoResult;
import org.xrpl.xrpl4j.model.client.common.LedgerIndex;
import org.xrpl.xrpl4j.model.client.transactions.SubmitResult;
import org.xrpl.xrpl4j.model.client.transactions.TransactionRequestParams;
import org.xrpl.xrpl4j.model.client.transactions.TransactionResult;
import org.xrpl.xrpl4j.model.immutables.FluentCompareTo;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.Transaction;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;
import org.xrpl.xrpl4j.wallet.Wallet;

public class XrplSendTransactionTool extends DefaultApplicationPlugin {

    @Override
    public String getName() {
        return "XRPL Send Transaction Tool";
    }

    @Override
    public String getVersion() {
        return "7.0.0";
    }

    @Override
    public String getDescription() {
        return "Send funds from one wallet to another on the XRP Ledger.";
    }
    
    @Override
    public Object execute(Map props) {
        Object result = null;
        WorkflowAssignment wfAssignment = (WorkflowAssignment) props.get("workflowAssignment");
        String rippledServer = getPropertyString("rippledServer");
        String rippledUrl = getPropertyString("rippledUrl");
        boolean isTest = false;
        
        if ("testnet".equals(rippledServer) || "devnet".equals(rippledServer)) {
            isTest = true;
        }
        
        final String originAddress = WorkflowUtil.processVariable(getPropertyString("originAddress"), "", wfAssignment);
        final String walletSeed = XrplUtil.decrypt(WorkflowUtil.processVariable(getPropertyString("walletSeed"), "", wfAssignment));
        final String destinationAddress = WorkflowUtil.processVariable(getPropertyString("destinationAddress"), "", wfAssignment);
        final String amount = WorkflowUtil.processVariable(getPropertyString("amount"), "", wfAssignment);
        
        try {
            XrplClient client = XrplUtil.getXrplClient(rippledServer, rippledUrl);
            
            if (client != null) {
                
                final Wallet originWallet = XrplUtil.getWalletFromSeed(walletSeed, isTest);
                //Ensure seed matches user intended origin address
                if (!originAddress.equals(originWallet.classicAddress().toString())) {
                    LogUtil.warn(getClass().getName(), "Transaction failed! Origin wallet address encountered invalid seed value.");
                    return null;
                }
                
                LedgerIndex latestValidatedLedgerIndex = XrplUtil.getLatestValidatedLedgerIndex(client);
                final UnsignedInteger lastLedgerSequence = XrplUtil.getLastLedgerSequence(latestValidatedLedgerIndex);
                
                final Payment payment = constructPayment(client, originWallet, destinationAddress, amount, lastLedgerSequence);
                
                if (payment == null) {
                    return null;
                }
                
                final SignedTransaction<Payment> signedTransaction = signUsingSingleKeySignatureService(originWallet, payment);

                final SubmitResult<Transaction> submitResult = client.submit(signedTransaction);
                
                //Wait for validation
                TransactionResult<Payment> transactionResult = null;

                boolean transactionValidated = false;
                boolean transactionExpired = false;
                while (!transactionValidated && !transactionExpired) {
                    Thread.sleep(XrplUtil.TX_PROCESSING_WAIT_TIME_MILLISECONDS);
                    
                    latestValidatedLedgerIndex = XrplUtil.getLatestValidatedLedgerIndex(client);

                    transactionResult = client.transaction(
                        TransactionRequestParams.of(signedTransaction.hash()),
                        Payment.class
                    );

                    if (transactionResult.validated()) {
                        transactionValidated = true;
                    } else {
                        final boolean lastLedgerSequenceHasPassed = FluentCompareTo.
                          is(latestValidatedLedgerIndex.unsignedLongValue())
                          .greaterThan(UnsignedLong.valueOf(lastLedgerSequence.intValue()));
                        if (lastLedgerSequenceHasPassed) {
//                            LogUtil.info(getClass().getName(), "LastLedgerSequence has passed. Last tx response: " + transactionResult);
                            transactionExpired = true;
                        } else {
//                            LogUtil.info(getClass().getName(), "Payment not yet validated.");
                        }
                    }
                }

                storeToWorkflowVariable(wfAssignment, props, transactionResult);
                
                result = transactionResult;
            }

            return result;
        } catch (Exception ex) {
            LogUtil.error(getClass().getName(), ex, "Error executing plugin...");
            return null;
        }
    }

    private SignedTransaction signUsingSingleKeySignatureService(Wallet originWallet, Payment payment) throws JsonRpcClientErrorException {
        PrivateKey privateKey = PrivateKey.fromBase16EncodedPrivateKey(originWallet.privateKey().get());
        SignatureService signatureService = new SingleKeySignatureService(privateKey);

        return signatureService.sign(KeyMetadata.EMPTY, payment);
    }

    private Payment constructPayment(XrplClient client, Wallet originWallet, String destinationAddress, String amount, UnsignedInteger lastLedgerSequence) throws JsonRpcClientErrorException {
        final AccountInfoRequestParams requestParams = AccountInfoRequestParams
            .builder().ledgerIndex(LedgerIndex.VALIDATED)
            .account(originWallet.classicAddress())
            .build();
        final AccountInfoResult accountInfoResult = client.accountInfo(requestParams);
        if (accountInfoResult.validated()) {
            final UnsignedInteger sequence = accountInfoResult.accountData().sequence();
        
            //Get current ledger fee
            final XrpCurrencyAmount openLedgerFee = XrplUtil.getCurrentOpenLedgerFeeInDrops(client);

            //Amount is limited to max 6 decimal places
            return Payment.builder()
                .account(originWallet.classicAddress())
                .destination(Address.of(destinationAddress))
                .amount(XrpCurrencyAmount.ofXrp(new BigDecimal(amount)))
                .fee(openLedgerFee)
                .sequence(sequence)
                .lastLedgerSequence(lastLedgerSequence)
                .signingPublicKey(originWallet.publicKey())
                .build();
        } else {
            LogUtil.warn(getClass().getName(), "Transaction failed! Origin account data is not from validated ledger!");
            return null;
        }
    }
    
    protected void storeToWorkflowVariable(WorkflowAssignment wfAssignment, Map properties, TransactionResult transactionResult) {
        String rippledServer = getPropertyString("rippledServer");
        
        String responseStatusVar = getPropertyString("wfResponseStatus");
        String transactionUrlVar = getPropertyString("wfTransactionExplorerUrl");
        
        ApplicationContext ac = AppUtil.getApplicationContext();
        WorkflowManager workflowManager = (WorkflowManager) ac.getBean("workflowManager");
        
        storeValuetoActivityVar(workflowManager, wfAssignment.getActivityId(), responseStatusVar, transactionResult.status().get());
        storeValuetoActivityVar(workflowManager, wfAssignment.getActivityId(), transactionUrlVar, XrplUtil.getTransactionExplorerUrl(rippledServer, transactionResult.transaction().hash().get().value()));
    }
    
    private void storeValuetoActivityVar(WorkflowManager workflowManager, String activityId, String variable, String value) {
        if (!variable.isEmpty()) {
            workflowManager.activityVariable(activityId, variable, value);
        }
    }

    @Override
    public String getLabel() {
        return getName();
    }

    @Override
    public String getClassName() {
        return getClass().getName();
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClass().getName(), "/properties/XrplSendTransactionTool.json", null, true, "messages/XrplMessages");
    }
    
}

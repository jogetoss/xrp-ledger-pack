package org.joget.marketplace;

import java.util.Map;
import okhttp3.HttpUrl;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import org.joget.plugin.base.DefaultApplicationPlugin;
import org.joget.workflow.model.WorkflowAssignment;
import org.joget.workflow.model.service.WorkflowManager;
import org.joget.workflow.util.WorkflowUtil;
import org.springframework.context.ApplicationContext;
import org.xrpl.xrpl4j.client.XrplClient;
import org.xrpl.xrpl4j.client.faucet.FaucetClient;
import org.xrpl.xrpl4j.client.faucet.FundAccountRequest;
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoRequestParams;
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoResult;
import org.xrpl.xrpl4j.wallet.DefaultWalletFactory;
import org.xrpl.xrpl4j.wallet.Wallet;
import org.xrpl.xrpl4j.wallet.WalletFactory;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.wallet.SeedWalletGenerationResult;

public class XrplGenerateWalletTool extends DefaultApplicationPlugin {

    @Override
    public String getName() {
        return "XRPL Generate Wallet Tool";
    }

    @Override
    public String getVersion() {
        return "7.0.0";
    }

    @Override
    public String getDescription() {
        return "Generates a new wallet on the XRP Ledger.";
    }

    @Override
    public Object execute(Map props) {
        Object result = null;
        String rippledServer = getPropertyString("rippledServer");
        String rippledUrl = getPropertyString("rippledUrl");
        boolean isTest = false;
        
        if ("testnet".equals(rippledServer) || "devnet".equals(rippledServer)) {
            isTest = true;
        }
        
        WorkflowAssignment wfAssignment = (WorkflowAssignment) props.get("workflowAssignment");
        
        try {
            XrplClient client = XrplUtil.getXrplClient(rippledServer, rippledUrl);
            
            if (client != null) {
                WalletFactory walletFactory = DefaultWalletFactory.getInstance();
                final SeedWalletGenerationResult walletGeneration = walletFactory.randomWallet(isTest);
                final Wallet wallet = walletGeneration.wallet();
                //Wallet Seed MUST be secured at all times.
                /* 
                    See XrplUtil encrypt & decrypt method to implement your preferred algo. Current way of encrypt/decrypt is just for POC. 
                */
                final String walletSeed = XrplUtil.encrypt(walletGeneration.seed());
                
                final Address classicAddress = wallet.classicAddress();
                
                if ("true".equals(getPropertyString("fundTestWallet"))) {
                    if ("testnet".equals(rippledServer)) {
                        fundTestWallet(XrplUtil.TESTNET_FAUCET_URL, classicAddress);
                    } else if ("devnet".equals(rippledServer)) {
                        fundTestWallet(XrplUtil.DEVNET_FAUCET_URL, classicAddress);
                    }
                }
                
                final AccountInfoRequestParams requestParams = AccountInfoRequestParams.of(classicAddress);
                
                //NOTE: Account that is not funded with the reserve amount won't be registered into ledger
                AccountInfoResult accountInfoResult;
                try {
                    accountInfoResult = client.accountInfo(requestParams);
                } catch (Exception ex) {
                    //LogUtil.warn(getClass().getName(), "Caution. Account not initialized...");
                    accountInfoResult = null;
                }
                
                storeToForm(wfAssignment, props, wallet, walletSeed);
                storeToWorkflowVariable(wfAssignment, props, wallet, accountInfoResult);
                
                result = accountInfoResult;
            }

            return result;
        } catch (Exception ex) {
            LogUtil.error(getClass().getName(), ex, "Error executing plugin...");
            return null;
        }
    }
    
    private void fundTestWallet(String faucetUrl, Address classicAddress) {
        final FaucetClient faucetClient = FaucetClient.construct(HttpUrl.get(faucetUrl));
        faucetClient.fundAccount(FundAccountRequest.of(classicAddress));
    }
    
    protected void storeToForm(WorkflowAssignment wfAssignment, Map properties, final Wallet wallet, final String walletSeed) {
        String formDefId = getPropertyString("formDefId");
        
        if (formDefId != null && formDefId.trim().length() > 0) {
            ApplicationContext ac = AppUtil.getApplicationContext();
            AppService appService = (AppService) ac.getBean("appService");
            AppDefinition appDef = (AppDefinition) properties.get("appDef");

            String walletSeedField = getPropertyString("walletSeedField");
            String walletOwnerField = getPropertyString("walletOwnerField");
            String walletOwnerValue = WorkflowUtil.processVariable(getPropertyString("walletOwnerValue"), "", wfAssignment);
            String isTestWalletField = getPropertyString("isTestWallet");
            String publicKeyField = getPropertyString("publicKey");
            String xAddressField = getPropertyString("xAddress");
            
            FormRowSet rowSet = new FormRowSet();
            
            FormRow row = new FormRow();
            
            //Wallet classic address set as Record ID
            row.setId(wallet.classicAddress().toString());
            row = addRow(row, walletSeedField, walletSeed);
            row = addRow(row, walletOwnerField, walletOwnerValue);
            row = addRow(row, isTestWalletField, Boolean.toString(wallet.isTest()));
            row = addRow(row, publicKeyField, wallet.publicKey());
            row = addRow(row, xAddressField, wallet.xAddress().toString());

            rowSet.add(row);

            if (rowSet.size() > 0) {
                appService.storeFormData(appDef.getId(), appDef.getVersion().toString(), formDefId, rowSet, null);
            }
        }
    }
    
    private FormRow addRow(FormRow row, String field, String value) {
        if (row != null && !field.isEmpty()) {
            row.put(field, value);
        }
        return row;
    }

    protected void storeToWorkflowVariable(WorkflowAssignment wfAssignment, Map properties, final Wallet wallet, final AccountInfoResult accountInfoResult) {
        String responseStatusVar = getPropertyString("wfResponseStatus");
        String isTestWalletVar = getPropertyString("wfIsTestWallet");
        
        ApplicationContext ac = AppUtil.getApplicationContext();
        WorkflowManager workflowManager = (WorkflowManager) ac.getBean("workflowManager");

        storeValuetoActivityVar(workflowManager, wfAssignment.getActivityId(), responseStatusVar, accountInfoResult != null ? accountInfoResult.status().get() : "Not initialized");
        storeValuetoActivityVar(workflowManager, wfAssignment.getActivityId(), isTestWalletVar, String.valueOf(wallet.isTest()));
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
        return AppUtil.readPluginResource(getClass().getName(), "/properties/XrplGenerateWalletTool.json", null, true, "messages/XrplMessages");
    }
}

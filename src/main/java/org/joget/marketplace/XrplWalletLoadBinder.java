package org.joget.marketplace;

import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.Element;
import org.joget.apps.form.model.FormBinder;
import org.joget.apps.form.model.FormData;
import org.joget.apps.form.model.FormLoadBinder;
import org.joget.apps.form.model.FormLoadElementBinder;
import org.joget.apps.form.model.FormRow;
import org.joget.apps.form.model.FormRowSet;
import org.joget.commons.util.LogUtil;
import org.joget.workflow.util.WorkflowUtil;
import org.xrpl.xrpl4j.client.XrplClient;
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoRequestParams;
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoResult;
import org.xrpl.xrpl4j.model.client.common.LedgerIndex;
import org.xrpl.xrpl4j.model.ledger.AccountRootObject;
import org.xrpl.xrpl4j.model.transactions.Address;

public class XrplWalletLoadBinder extends FormBinder implements FormLoadBinder, FormLoadElementBinder {

    @Override
    public String getName() {
        return "XRPL Wallet Load Binder";
    }

    @Override
    public String getVersion() {
        return "7.0.0";
    }

    @Override
    public String getDescription() {
        return "Load wallet data from the XRP Ledger into a form.";
    }

    @Override
    public FormRowSet load(Element element, String primaryKey, FormData formData) {
        FormRowSet rows = new FormRowSet();
        
        String rippledServer = getPropertyString("rippledServer");
        String rippledUrl = getPropertyString("rippledUrl");

        try {
            XrplClient client = XrplUtil.getXrplClient(rippledServer, rippledUrl);
            
            if (client != null) {
                final String walletAddress = WorkflowUtil.processVariable(getPropertyString("walletAddress"), "", null);
                
                //Prevent error thrown from empty value and invalid hash variable
                if (walletAddress.isEmpty() || walletAddress.startsWith("#")) {
                    return null;
                }
                
                final AccountInfoRequestParams requestParams = AccountInfoRequestParams
                    .builder().ledgerIndex(LedgerIndex.VALIDATED)
                    .account(Address.of(walletAddress))
                    .build();
                AccountInfoResult accountInfoResult = null;
                try {
                    accountInfoResult = client.accountInfo(requestParams);
                    if (!accountInfoResult.validated()) {
                        LogUtil.warn(getClass().getName(), "Caution. Account data not from validated ledger!");
                    }
                } catch (Exception ex) {
                    //NOTE: Account that is not funded with the reserve amount won't be registered into ledger
                    LogUtil.warn(getClass().getName(), "Account does not exist and/or not initialized...");
                    return null;
                }
                final AccountRootObject account = accountInfoResult.accountData();
                
                String isAccountValidatedField = getPropertyString("isAccountValidatedField");
                String balanceField = getPropertyString("balanceField");
                String lastRecentTxToThisAccField = getPropertyString("lastRecentTxToThisAccField");
                String lastRecentTxOfThisAccField = getPropertyString("lastRecentTxOfThisAccField");
                String accountDomainField = getPropertyString("accountDomainField");
                String accountEmailHashField = getPropertyString("accountEmailHashField");
                String ownerObjCountField = getPropertyString("ownerObjCountField");
                
                FormRow row = new FormRow();
                
                row = addRow(row, isAccountValidatedField, String.valueOf(accountInfoResult.validated()));
                row = addRow(row, balanceField, account.balance().toXrp().toString());
                row = addRow(row, lastRecentTxToThisAccField, account.previousTransactionId().value());
                row = addRow(row, lastRecentTxOfThisAccField, account.accountTransactionId().isPresent() ? account.accountTransactionId().get().value() : "");
                row = addRow(row, accountDomainField, account.domain().isPresent() ? account.domain().get() : "");
                row = addRow(row, accountEmailHashField, account.emailHash().isPresent() ? account.emailHash().get() : "");
                row = addRow(row, ownerObjCountField, account.ownerCount().toString());
                
                rows.add(row);
            }
            
            return rows;
        } catch (Exception ex) {
            LogUtil.error(getClass().getName(), ex, "Error executing plugin...");
            return null;
        }
    }
    
    private FormRow addRow(FormRow row, String field, String value) {
        if (row != null && !field.isEmpty()) {
            row.put(field, value);
        }
        return row;
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
        return AppUtil.readPluginResource(getClass().getName(), "/properties/XrplWalletLoadBinder.json", null, true, "messages/XrplMessages");
    }
}

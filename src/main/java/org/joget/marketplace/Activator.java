package org.joget.marketplace;

import java.util.ArrayList;
import java.util.Collection;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

public class Activator implements BundleActivator {

    protected Collection<ServiceRegistration> registrationList;

    @Override
    public void start(BundleContext context) {
        registrationList = new ArrayList<>();

        //Register plugin here
        registrationList.add(context.registerService(XrplGenerateWalletTool.class.getName(), new XrplGenerateWalletTool(), null));
        registrationList.add(context.registerService(XrplSendTransactionTool.class.getName(), new XrplSendTransactionTool(), null));
        registrationList.add(context.registerService(XrplWalletLoadBinder.class.getName(), new XrplWalletLoadBinder(), null));
    }

    @Override
    public void stop(BundleContext context) {
        for (ServiceRegistration registration : registrationList) {
            registration.unregister();
        }
    }
}
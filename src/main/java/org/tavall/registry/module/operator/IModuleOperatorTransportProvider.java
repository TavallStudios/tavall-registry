package org.tavall.registry.module.operator;

/**
 * Service-provider contract a runtime ships so operator tools can reach its modules. Providers are
 * discovered with {@link java.util.ServiceLoader} under
 * {@code META-INF/services/org.tavall.registry.module.operator.IModuleOperatorTransportProvider}.
 */
public interface IModuleOperatorTransportProvider {

    /** @return transport type this provider opens, matching {@link ModuleRuntimeTarget#transport()} */
    String transportType();

    /**
     * Opens an authenticated transport to the target.
     *
     * @param target runtime target using this provider's transport type
     * @return open transport
     */
    IModuleOperatorTransport open(ModuleRuntimeTarget target);
}

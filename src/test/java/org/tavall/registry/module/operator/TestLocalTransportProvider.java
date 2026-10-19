package org.tavall.registry.module.operator;

/** Test provider proving ServiceLoader discovery of runtime transports. */
public final class TestLocalTransportProvider implements IModuleOperatorTransportProvider {

    @Override
    public String transportType() {
        return "local-test";
    }

    @Override
    public IModuleOperatorTransport open(ModuleRuntimeTarget target) {
        throw new UnsupportedOperationException("discovery-only test provider");
    }
}

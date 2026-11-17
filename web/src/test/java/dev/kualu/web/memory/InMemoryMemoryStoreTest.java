package dev.kualu.web.memory;

class InMemoryMemoryStoreTest extends MemoryStoreContractTest {

    @Override
    protected MemoryStore newStore() {
        return new InMemoryMemoryStore();
    }
}

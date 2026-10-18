package agent.next.device;

/** Runs the device-api contract against the in-memory reference implementation. */
public final class DeviceApiHostTest {

    private DeviceApiHostTest() {
    }

    public static void main(String[] args) throws Exception {
        FakeDeviceApi api = new FakeDeviceApi();
        DeviceApiContract.run(api);
        api.close();

        DeviceException closed = TestSupport.expect(DeviceException.class,
                () -> api.capabilities(), "closed api rejects capabilities");
        TestSupport.check(closed.code() == DeviceException.Code.UNAVAILABLE,
                "closed api reports UNAVAILABLE");
        api.close();
        System.out.println("DeviceApiHostTest: PASS (contract on FakeDeviceApi)");
    }
}

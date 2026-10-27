package org.cloudbus.cloudsim.examples;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.power.*;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.*;

public class Project {

    // ---------- experiment parameters ----------
    static final int    HOSTS = 6, HOST_PES = 2, HOST_PE_MIPS = 3000;   // host = 6000 MIPS
    static final int    VMS = 12;
    static final int    VM_MIPS = 3000, VM_RAM = 2048;
    static final double SIM_END = 700, SCHED_INTERVAL = 5;
    static final double WORK_MI = 1200;                  // work per request (MI)
    static final double R_SLA = 2.0, R_MAX = 60.0;       // seconds
    static final long   CLOUDLET_LEN = 1_000_000_000_000_000L;   // never completes
    static final double[] WEIGHT = new double[VMS];
    static {
        for (int v = 0; v < VMS; v++)
            WEIGHT[v] = 0.6 + 0.4 * ((v * 7) % VMS) / (VMS - 1.0);
    }

    static String WORKLOAD = "spike";    // "spike" or "ramp", set by main()

    // ---------- workload intensity over time ----------
    static double phase(double t) {
        if (WORKLOAD.equals("ramp")) {
            if (t < 100) return 0.25;
            if (t < 350) return 0.25 + 0.60 * (t - 100) / 250.0;   // 0.25 -> 0.85
            if (t < 450) return 0.85;
            if (t < 600) return 0.85 - 0.50 * (t - 450) / 150.0;   // 0.85 -> 0.35
            return 0.35;
        }
        if (t < 150) return 0.25;     // low
        if (t < 300) return 0.90;     // spike
        if (t < 450) return 0.45;     // medium
        return 0.80;                  // second spike
    }
    static double load(int vm, double t) {
        double u = WEIGHT[vm] * phase(t) * (1 + 0.10 * Math.sin(0.05 * t + vm));
        return Math.max(0.02, Math.min(1.0, u));
    }
    static class Workload implements UtilizationModel {
        final int vm;
        Workload(int vm) { this.vm = vm; }
        @Override public double getUtilization(double time) { return load(vm, time); }
    }

    // ---------- datacenter: samples metrics and logs migrations ----------
    static class MonitoredDatacenter extends PowerDatacenter {
        final PrintWriter out, migLog;
        double next = 0;

        MonitoredDatacenter(String n, DatacenterCharacteristics c, VmAllocationPolicy p,
                            List<Storage> s, double interval,
                            PrintWriter out, PrintWriter migLog) throws Exception {
            super(n, c, p, s, interval);
            this.out = out;
            this.migLog = migLog;
        }

        // called when a migration completes: log VM, source host, target host
        @SuppressWarnings("unchecked")
        @Override protected void processVmMigrate(SimEvent ev, boolean ack) {
            Object data = ev.getData();
            if (migLog != null && data instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) data;
                Vm vm = (Vm) m.get("vm");
                Host to = (Host) m.get("host");
                int from = (vm.getHost() == null) ? -1 : vm.getHost().getId();
                migLog.printf(Locale.US, "%.1f,%d,%d,%d%n", CloudSim.clock(), vm.getId(), from, to.getId());
            }
            super.processVmMigrate(ev, ack);
        }

        @Override protected void updateCloudletProcessing() {
            super.updateCloudletProcessing();
            double t = CloudSim.clock();
            if (t + 1e-9 < next) return;
            next = t + SCHED_INTERVAL;
            sample(t);
        }

        void sample(double t) {
            double cpuSum = 0, memUsed = 0, memCap = 0, rtNum = 0, rtDen = 0, tp = 0;
            int active = 0, saturated = 0, viol = 0;
            for (PowerHost h : this.<PowerHost>getHostList()) {
                if (h.getVmList().isEmpty()) continue;
                active++;

                // count a VM only on the host it currently runs on
                List<Vm> resident = new ArrayList<>();
                for (Vm vm : h.getVmList())
                    if (vm.getHost() == h) resident.add(vm);

                double req = 0;
                for (Vm vm : resident) req += load(vm.getId(), t) * VM_MIPS;
                double U = req / h.getTotalMips();
                double s = Math.min(1.0, 1.0 / Math.max(U, 1e-9));
                cpuSum += Math.min(U, 1.0);
                if (U > 1.0) saturated++;
                memCap += h.getRam();

                for (Vm vm : resident) {
                    double u = load(vm.getId(), t);
                    memUsed += VM_RAM * (0.4 + 0.6 * u);
                    double lambda = u * VM_MIPS / WORK_MI;
                    double S = WORK_MI / VM_MIPS;
                    double rho = u / s;
                    double R = (rho >= 0.98) ? R_MAX : Math.min(R_MAX, (S / s) / (1 - rho));
                    rtNum += R * lambda;
                    rtDen += lambda;
                    tp += lambda * Math.min(1.0, s / u);
                    if (R > R_SLA) viol++;
                }
            }
            out.printf(Locale.US, "%.1f,%.4f,%.4f,%.3f,%.3f,%d,%d,%d,%d%n",
                t, active == 0 ? 0 : cpuSum / active, memCap == 0 ? 0 : memUsed / memCap,
                rtDen == 0 ? 0 : rtNum / rtDen, tp, viol,
                getMigrationCount(), saturated, active);
        }
    }

    // ---------- one scenario ----------
    static void run(boolean dynamic, double up) throws Exception {
        CloudSim.init(1, Calendar.getInstance(), false);

        List<PowerHostUtilizationHistory> hosts = new ArrayList<>();
        for (int i = 0; i < HOSTS; i++) {
            List<Pe> pes = new ArrayList<>();
            for (int p = 0; p < HOST_PES; p++)
                pes.add(new Pe(p, new PeProvisionerSimple(HOST_PE_MIPS)));
            hosts.add(new PowerHostUtilizationHistory(i, new RamProvisionerSimple(16384),
                    new BwProvisionerSimple(1_000_000), 1_000_000, pes,
                    new VmSchedulerTimeSharedOverSubscription(pes),
                    new PowerModelLinear(250, 0.4)));
        }

        VmAllocationPolicy policy = dynamic
            ? new PowerVmAllocationPolicyMigrationStaticThreshold(hosts,
                    new PowerVmSelectionPolicyMinimumUtilization(), up)
            : new PowerVmAllocationPolicySimple(hosts);

        String pct = String.valueOf(Math.round(up * 100));
        String base = "results/" + WORKLOAD + "_";
        String file = base + (dynamic ? "dynamic_" + pct : "static") + ".csv";
        new java.io.File("results").mkdirs();
        PrintWriter out = new PrintWriter(new FileWriter(file));
        out.println("time,cpu_util,mem_util,resp_time,throughput,sla_violations,"
                  + "migrations,saturated_hosts,active_hosts");

        PrintWriter mig = null;
        if (dynamic) {
            mig = new PrintWriter(new FileWriter(base + "migrations_" + pct + ".csv"));
            mig.println("time_completed,vm,from_host,to_host");
        }

        DatacenterCharacteristics ch = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hosts, 10.0, 3.0, 0.05, 0.001, 0.0);
        MonitoredDatacenter dc = new MonitoredDatacenter("DC", ch, policy,
                new LinkedList<Storage>(), SCHED_INTERVAL, out, mig);
        dc.setDisableMigrations(!dynamic);

        DatacenterBroker broker = new PowerDatacenterBroker("Broker");

        List<Vm> vms = new ArrayList<>();
        List<Cloudlet> cls = new ArrayList<>();
        for (int i = 0; i < VMS; i++) {
            vms.add(new PowerVm(i, broker.getId(), VM_MIPS, 1, VM_RAM, 10_000, 10_000, 1, "Xen",
                    new CloudletSchedulerDynamicWorkload(VM_MIPS, 1), SCHED_INTERVAL));
            Cloudlet c = new Cloudlet(i, CLOUDLET_LEN, 1, 0, 0,
                    new Workload(i), new UtilizationModelFull(), new UtilizationModelFull());
            c.setUserId(broker.getId());
            c.setVmId(i);
            cls.add(c);
        }
        broker.submitVmList(vms);
        broker.submitCloudletList(cls);

        CloudSim.terminateSimulation(SIM_END);
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        out.close();
        if (mig != null) mig.close();
        System.out.printf("%s %s (T_up=%s) -> %s, migrations: %d%n", WORKLOAD,
                dynamic ? "DYNAMIC" : "STATIC", pct, file, dc.getMigrationCount());
    }

    public static void main(String[] args) throws Exception {
        Log.disable();
        double[] ups = {0.8};                 // threshold sweep: use {0.6, 0.7, 0.8, 0.9}
        for (String wl : new String[] {"spike", "ramp"}) {
            WORKLOAD = wl;
            run(false, 0.8);                  // static (threshold is ignored)
            for (double up : ups) run(true, up);
        }
    }
}
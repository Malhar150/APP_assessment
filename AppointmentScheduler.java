import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
public class AppointmentScheduler {
    enum ProviderType { DOCTOR, ADVISOR }
    static class Provider {
        final int id; final String name; final ProviderType type;
        Provider(int id, String name, ProviderType type) { this.id = id; this.name = name; this.type = type; }
        @Override
        public String toString() { return (type == ProviderType.DOCTOR ? "Dr. " : "Prof. ") + name + " [" + type + "]"; }
    }
    static class Slot {
        final int id; final Provider provider; final LocalDateTime start, end;
        Appointment booking; // null = available
        Slot(int id, Provider p, LocalDateTime s, LocalDateTime e) { this.id = id; provider = p; start = s; end = e; }
        boolean isAvailable() { return booking == null; }
        boolean overlaps(LocalDateTime s, LocalDateTime e) { return start.isBefore(e) && s.isBefore(end); }
    }
    static class Appointment {
        final int id; final Slot slot; final String person, reason;
        Appointment(int id, Slot slot, String person, String reason) { this.id = id; this.slot = slot; this.person = person; this.reason = reason; }
    }
    static class Scheduler {
        private final Map<Integer, Provider> providers = new LinkedHashMap<>();
        private final Map<Integer, Slot> slots = new TreeMap<>();
        private final Map<Integer, Appointment> appointments = new LinkedHashMap<>();
        private int nextProvider = 1, nextSlot = 1, nextAppt = 1;
        synchronized Provider addProvider(String name, ProviderType type) {
            Provider p = new Provider(nextProvider++, name, type);
            providers.put(p.id, p);
            return p;
        }
        synchronized Slot addSlot(int providerId, LocalDateTime start, LocalDateTime end) {
            Provider p = providers.get(providerId);
            if (p == null) throw new IllegalArgumentException("No provider with id " + providerId);
            if (!end.isAfter(start)) throw new IllegalArgumentException("End time must be after start time");
            if (start.isBefore(LocalDateTime.now())) throw new IllegalArgumentException("Cannot create a slot in the past");
            for (Slot s : slots.values())
                if (s.provider.id == providerId && s.overlaps(start, end))
                    throw new IllegalStateException("Overlaps existing slot #" + s.id + " for " + p);
            Slot s = new Slot(nextSlot++, p, start, end);
            slots.put(s.id, s);
            return s;
        }
            synchronized Appointment book(int slotId, String person, String reason) {
            Slot s = slots.get(slotId);
            if (s == null) throw new IllegalArgumentException("No slot with id " + slotId);
            if (!s.isAvailable()) throw new IllegalStateException("Slot #" + slotId + " is already booked (double booking blocked)");
            if (s.start.isBefore(LocalDateTime.now())) throw new IllegalStateException("Slot #" + slotId + " has already started/passed");
            if (person == null || person.isBlank()) throw new IllegalArgumentException("Name is required");
            for (Appointment a : appointments.values())
                if (a.person.equalsIgnoreCase(person.trim()) && a.slot.overlaps(s.start, s.end))
                    throw new IllegalStateException(person + " already has appointment #" + a.id + " at an overlapping time");
            Appointment a = new Appointment(nextAppt++, s, person.trim(), reason);
            s.booking = a;
            appointments.put(a.id, a);
            return a;
        }
        synchronized void cancel(int apptId, String person) {
            Appointment a = appointments.get(apptId);
            if (a == null) throw new IllegalArgumentException("No appointment with id " + apptId);
            if (!a.person.equalsIgnoreCase(person.trim())) throw new IllegalStateException("Only " + a.person + " can cancel this appointment");
            a.slot.booking = null;          // slot becomes available again
            appointments.remove(apptId);
        }
        synchronized void removeSlot(int slotId) {
            Slot s = slots.get(slotId);
            if (s == null) throw new IllegalArgumentException("No slot with id " + slotId);
            if (!s.isAvailable()) throw new IllegalStateException("Slot is booked; cancel appointment #" + s.booking.id + " first");
            slots.remove(slotId);
        }
        synchronized List<Slot> availableSlots(Integer providerId) {
            List<Slot> out = new ArrayList<>();
            for (Slot s : slots.values())
                if (s.isAvailable() && !s.start.isBefore(LocalDateTime.now()) && (providerId == null || s.provider.id == providerId))
                    out.add(s);
            out.sort(Comparator.comparing(x -> x.start));
            return out;
        }
        synchronized List<Appointment> appointmentsOf(String person) {
            List<Appointment> out = new ArrayList<>();
            for (Appointment a : appointments.values())
                if (person == null || a.person.equalsIgnoreCase(person.trim())) out.add(a);
            return out;
        }
        synchronized Collection<Provider> providers() { return providers.values(); }
    }
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final Scanner in = new Scanner(System.in);
    public static void main(String[] args) {
        Scheduler sc = new Scheduler();
        seed(sc);
        while (true) {
            System.out.println("\n===== Appointment Scheduler =====");
            System.out.println("1. List providers        2. Add provider");
            System.out.println("3. Add available slot    4. View available slots");
            System.out.println("5. Book appointment      6. Cancel appointment");
            System.out.println("7. My appointments       8. All appointments");
            System.out.println("9. Remove a free slot    0. Exit");
            int ch = readInt("Choice: ");
            try {
                switch (ch) {
                    case 1 -> sc.providers().forEach(p -> System.out.println(p.id + ". " + p));
                    case 2 -> {
                        String name = readLine("Name: ");
                        int t = readInt("Type (1 = Doctor, 2 = Faculty Advisor): ");
                        System.out.println("Added: " + sc.addProvider(name, t == 1 ? ProviderType.DOCTOR : ProviderType.ADVISOR));
                    }
                    case 3 -> {
                        int pid = readInt("Provider id: ");
                        LocalDateTime s = readTime("Start (yyyy-MM-dd HH:mm): ");
                        int mins = readInt("Duration in minutes: ");
                        Slot slot = sc.addSlot(pid, s, s.plusMinutes(mins));
                        System.out.println("Slot #" + slot.id + " created.");
                    }
                    case 4 -> {
                        String p = readLine("Provider id (Enter for all): ").trim();
                        printSlots(sc.availableSlots(p.isBlank() ? null : Integer.valueOf(p)));
                    }
                    case 5 -> {
                        int sid = readInt("Slot id: ");
                        String name = readLine("Your name: ");
                        String reason = readLine("Reason: ");
                        Appointment a = sc.book(sid, name, reason);
                        System.out.println("Booked! Appointment #" + a.id + " with " + a.slot.provider + " at " + a.slot.start.format(FMT));
                    }
                    case 6 -> {
                        int aid = readInt("Appointment id: ");
                        sc.cancel(aid, readLine("Your name: "));
                        System.out.println("Cancelled. Slot is available again.");
                    }
                    case 7 -> printAppts(sc.appointmentsOf(readLine("Your name: ")));
                    case 8 -> printAppts(sc.appointmentsOf(null));
                    case 9 -> { sc.removeSlot(readInt("Slot id: ")); System.out.println("Slot removed."); }
                    case 0 -> { System.out.println("Bye!"); return; }
                    default -> System.out.println("Invalid choice.");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }
    static void seed(Scheduler sc) {
        Provider d = sc.addProvider("Sharma", ProviderType.DOCTOR);
        Provider f = sc.addProvider("Iyer", ProviderType.ADVISOR);
        LocalDateTime base = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        for (int i = 0; i < 3; i++) {
            sc.addSlot(d.id, base.plusMinutes(30L * i), base.plusMinutes(30L * (i + 1)));
            sc.addSlot(f.id, base.plusMinutes(30L * i), base.plusMinutes(30L * (i + 1)));
        }
    }
    static void printSlots(List<Slot> list) {
        if (list.isEmpty()) { System.out.println("No available slots."); return; }
        for (Slot s : list)
            System.out.printf("Slot #%d | %s | %s - %s%n", s.id, s.provider, s.start.format(FMT), s.end.toLocalTime());
    }
    static void printAppts(List<Appointment> list) {
        if (list.isEmpty()) { System.out.println("No appointments."); return; }
        for (Appointment a : list)
            System.out.printf("Appt #%d | %s | %s | %s | reason: %s%n", a.id, a.person, a.slot.provider, a.slot.start.format(FMT), a.reason);
    }
    static String readLine(String prompt) {
        System.out.print(prompt);
        return in.hasNextLine() ? in.nextLine() : "0";
    }
    static int readInt(String prompt) {
        while (true) {
            try { return Integer.parseInt(readLine(prompt).trim()); }
            catch (NumberFormatException e) { System.out.println("Enter a valid number."); }
        }
    }
    static LocalDateTime readTime(String prompt) {
        while (true) {
            try { return LocalDateTime.parse(readLine(prompt).trim(), FMT); }
            catch (DateTimeParseException e) { System.out.println("Use format yyyy-MM-dd HH:mm, e.g. 2026-11-02 14:30"); }
        }
    }
}
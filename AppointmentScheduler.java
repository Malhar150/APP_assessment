import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.Scanner;

public class AppointmentScheduler {
    static final String DB="appointments";
    static final String SERVER="jdbc:mysql://localhost:3306/?serverTimezone=UTC";
    static final String URL="jdbc:mysql://localhost:3306/"+DB+"?serverTimezone=UTC";
    static final String USER="root";
    static final String PASSWORD="YOUR_MYSQL_PASSWORD";
    static final Scanner sc=new Scanner(System.in);
    static final DateTimeFormatter fmt=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static void main(String[] args) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            setup();
            menu();
        } catch(Exception e) {
            System.out.println("Error: "+e.getMessage());
        }
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL,USER,PASSWORD);
    }

    static void setup() throws SQLException {
        try(Connection c=DriverManager.getConnection(SERVER,USER,PASSWORD);
            Statement s=c.createStatement()) {
            s.executeUpdate("CREATE DATABASE IF NOT EXISTS "+DB);
        }
        try(Connection c=connect();Statement s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS providers(id INT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(100) NOT NULL,type VARCHAR(20) NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS slots(id INT PRIMARY KEY AUTO_INCREMENT,provider_id INT NOT NULL,start_time DATETIME NOT NULL,end_time DATETIME NOT NULL,CHECK(end_time>start_time),FOREIGN KEY(provider_id) REFERENCES providers(id),INDEX(provider_id,start_time,end_time))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS appointments(id INT PRIMARY KEY AUTO_INCREMENT,slot_id INT NOT NULL UNIQUE,patient_name VARCHAR(100) NOT NULL,reason VARCHAR(255),booked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(slot_id) REFERENCES slots(id))");
        }
    }

    static void menu() {
        while(true) {
            System.out.println("\n===== Appointment Scheduler =====");
            System.out.println("1. List providers");
            System.out.println("2. Add provider");
            System.out.println("3. Add available slot");
            System.out.println("4. View available slots");
            System.out.println("5. Book appointment");
            System.out.println("6. Cancel appointment");
            System.out.println("7. My appointments");
            System.out.println("8. All appointments");
            System.out.println("9. Remove free slot");
            System.out.println("0. Exit");
            int choice=readInt("Choice: ");
            try {
                switch(choice) {
                    case 1 -> listProviders();
                    case 2 -> addProvider();
                    case 3 -> addSlot();
                    case 4 -> viewSlots();
                    case 5 -> bookAppointment();
                    case 6 -> cancelAppointment();
                    case 7 -> listAppointments(readLine("Your name: ").trim());
                    case 8 -> listAppointments(null);
                    case 9 -> removeSlot(readInt("Slot ID: "));
                    case 0 -> {
                        System.out.println("Bye!");
                        return;
                    }
                    default -> System.out.println("Invalid choice.");
                }
            } catch(SQLException e) {
                if("23000".equals(e.getSQLState()))
                    System.out.println("Slot is already booked or referenced.");
                else
                    System.out.println("Database error: "+e.getMessage());
            } catch(IllegalArgumentException | IllegalStateException e) {
                System.out.println("Error: "+e.getMessage());
            }
        }
    }

    static void listProviders() throws SQLException {
        try(Connection c=connect();
            Statement s=c.createStatement();
            ResultSet r=s.executeQuery("SELECT * FROM providers ORDER BY id")) {
            boolean found=false;
            while(r.next()) {
                found=true;
                System.out.println(r.getInt("id")+". "+title(r.getString("type"),r.getString("name")));
            }
            if(!found) System.out.println("No providers found.");
        }
    }

    static void addProvider() throws SQLException {
        String name=readLine("Name: ").trim();
        if(name.isEmpty()) throw new IllegalArgumentException("Name is required.");
        int choice=readInt("1. Doctor  2. Faculty Advisor: ");
        if(choice!=1 && choice!=2) throw new IllegalArgumentException("Invalid provider type.");
        String type=choice==1?"DOCTOR":"ADVISOR";
        try(Connection c=connect();
            PreparedStatement p=c.prepareStatement("INSERT INTO providers(name,type) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1,name);
            p.setString(2,type);
            p.executeUpdate();
            try(ResultSet r=p.getGeneratedKeys()) {
                if(r.next()) System.out.println("Provider added. ID: "+r.getInt(1));
            }
        }
    }

    static void addSlot() throws SQLException {
        int providerId=readInt("Provider ID: ");
        LocalDateTime start=readTime("Start (yyyy-MM-dd HH:mm): ");
        int duration=readInt("Duration in minutes: ");
        if(duration<=0) throw new IllegalArgumentException("Duration must be positive.");
        if(!start.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("Cannot add a past slot.");
        LocalDateTime end=start.plusMinutes(duration);
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("SELECT id FROM providers WHERE id=? FOR UPDATE")) {
                    p.setInt(1,providerId);
                    try(ResultSet r=p.executeQuery()) {
                        if(!r.next()) throw new IllegalArgumentException("Provider not found.");
                    }
                }
                try(PreparedStatement p=c.prepareStatement("SELECT id FROM slots WHERE provider_id=? AND start_time<? AND end_time>? LIMIT 1")) {
                    p.setInt(1,providerId);
                    p.setObject(2,end);
                    p.setObject(3,start);
                    try(ResultSet r=p.executeQuery()) {
                        if(r.next()) throw new IllegalStateException("This time overlaps slot #"+r.getInt(1));
                    }
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO slots(provider_id,start_time,end_time) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                    p.setInt(1,providerId);
                    p.setObject(2,start);
                    p.setObject(3,end);
                    p.executeUpdate();
                    try(ResultSet r=p.getGeneratedKeys()) {
                        if(r.next()) System.out.println("Slot created. ID: "+r.getInt(1));
                    }
                }
                c.commit();
            } catch(SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    static void viewSlots() throws SQLException {
        String input=readLine("Provider ID (Enter for all): ").trim();
        String sql="SELECT s.id,p.name,p.type,s.start_time,s.end_time FROM slots s JOIN providers p ON p.id=s.provider_id WHERE s.start_time>=? AND NOT EXISTS(SELECT 1 FROM appointments a WHERE a.slot_id=s.id)"+(input.isEmpty()?"":" AND s.provider_id=?")+" ORDER BY s.start_time";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)) {
            p.setObject(1,LocalDateTime.now());
            if(!input.isEmpty()) p.setInt(2,parseId(input));
            try(ResultSet r=p.executeQuery()) {
                boolean found=false;
                while(r.next()) {
                    found=true;
                    System.out.printf("Slot #%d | %s | %s - %s%n",r.getInt("id"),title(r.getString("type"),r.getString("name")),r.getTimestamp("start_time").toLocalDateTime().format(fmt),r.getTimestamp("end_time").toLocalDateTime().format(fmt));
                }
                if(!found) System.out.println("No available slots.");
            }
        }
    }

    static void bookAppointment() throws SQLException {
        int slotId=readInt("Slot ID: ");
        String name=readLine("Your name: ").trim();
        String reason=readLine("Reason: ").trim();
        if(name.isEmpty()) throw new IllegalArgumentException("Name is required.");
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                LocalDateTime start,end;
                try(PreparedStatement p=c.prepareStatement("SELECT start_time,end_time FROM slots WHERE id=? FOR UPDATE")) {
                    p.setInt(1,slotId);
                    try(ResultSet r=p.executeQuery()) {
                        if(!r.next()) throw new IllegalArgumentException("Slot not found.");
                        start=r.getTimestamp("start_time").toLocalDateTime();
                        end=r.getTimestamp("end_time").toLocalDateTime();
                    }
                }
                if(!start.isAfter(LocalDateTime.now())) throw new IllegalStateException("This slot has already started.");
                try(PreparedStatement p=c.prepareStatement("SELECT id FROM appointments WHERE slot_id=?")) {
                    p.setInt(1,slotId);
                    try(ResultSet r=p.executeQuery()) {
                        if(r.next()) throw new IllegalStateException("This slot is already booked.");
                    }
                }
                String sql="SELECT a.id FROM appointments a JOIN slots s ON s.id=a.slot_id WHERE LOWER(a.patient_name)=LOWER(?) AND s.start_time<? AND s.end_time>? LIMIT 1";
                try(PreparedStatement p=c.prepareStatement(sql)) {
                    p.setString(1,name);
                    p.setObject(2,end);
                    p.setObject(3,start);
                    try(ResultSet r=p.executeQuery()) {
                        if(r.next()) throw new IllegalStateException("You already have an overlapping appointment.");
                    }
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO appointments(slot_id,patient_name,reason) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                    p.setInt(1,slotId);
                    p.setString(2,name);
                    p.setString(3,reason);
                    p.executeUpdate();
                    try(ResultSet r=p.getGeneratedKeys()) {
                        if(r.next()) System.out.println("Appointment booked. ID: "+r.getInt(1));
                    }
                }
                c.commit();
            } catch(SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    static void cancelAppointment() throws SQLException {
        int id=readInt("Appointment ID: ");
        String name=readLine("Name used for booking: ").trim();
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                String owner=null;
                try(PreparedStatement p=c.prepareStatement("SELECT patient_name FROM appointments WHERE id=? FOR UPDATE")) {
                    p.setInt(1,id);
                    try(ResultSet r=p.executeQuery()) {
                        if(r.next()) owner=r.getString("patient_name");
                    }
                }
                if(owner==null) throw new IllegalArgumentException("Appointment not found.");
                if(!owner.equalsIgnoreCase(name)) throw new IllegalStateException("Name does not match the booking.");
                try(PreparedStatement p=c.prepareStatement("DELETE FROM appointments WHERE id=?")) {
                    p.setInt(1,id);
                    p.executeUpdate();
                }
                c.commit();
                System.out.println("Appointment cancelled. Slot is available again.");
            } catch(SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    static void listAppointments(String name) throws SQLException {
        String sql="SELECT a.id,a.patient_name,a.reason,p.name AS provider_name,p.type,s.id AS slot_id,s.start_time,s.end_time FROM appointments a JOIN slots s ON s.id=a.slot_id JOIN providers p ON p.id=s.provider_id"+(name==null?"":" WHERE LOWER(a.patient_name)=LOWER(?)")+" ORDER BY s.start_time";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)) {
            if(name!=null) p.setString(1,name);
            try(ResultSet r=p.executeQuery()) {
                boolean found=false;
                while(r.next()) {
                    found=true;
                    System.out.printf("Appointment #%d | Slot #%d | %s | %s | %s - %s | Reason: %s%n",r.getInt("id"),r.getInt("slot_id"),r.getString("patient_name"),title(r.getString("type"),r.getString("provider_name")),r.getTimestamp("start_time").toLocalDateTime().format(fmt),r.getTimestamp("end_time").toLocalDateTime().format(fmt),r.getString("reason"));
                }
                if(!found) System.out.println("No appointments found.");
            }
        }
    }

    static void removeSlot(int id) throws SQLException {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("SELECT id FROM slots WHERE id=? FOR UPDATE")) {
                    p.setInt(1,id);
                    try(ResultSet r=p.executeQuery()) {
                        if(!r.next()) throw new IllegalArgumentException("Slot not found.");
                    }
                }
                try(PreparedStatement p=c.prepareStatement("SELECT id FROM appointments WHERE slot_id=?")) {
                    p.setInt(1,id);
                    try(ResultSet r=p.executeQuery()) {
                        if(r.next()) throw new IllegalStateException("Cancel the appointment before removing this slot.");
                    }
                }
                try(PreparedStatement p=c.prepareStatement("DELETE FROM slots WHERE id=?")) {
                    p.setInt(1,id);
                    p.executeUpdate();
                }
                c.commit();
                System.out.println("Slot removed.");
            } catch(SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    static String title(String type,String name) {
        return (type.equals("DOCTOR")?"Dr. ":"Prof. ")+name;
    }

    static String readLine(String message) {
        System.out.print(message);
        return sc.nextLine();
    }

    static int readInt(String message) {
        while(true) {
            try {
                return Integer.parseInt(readLine(message).trim());
            } catch(NumberFormatException e) {
                System.out.println("Enter a valid number.");
            }
        }
    }

    static int parseId(String value) {
        try {
            return Integer.parseInt(value);
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid provider ID.");
        }
    }

    static LocalDateTime readTime(String message) {
        while(true) {
            try {
                return LocalDateTime.parse(readLine(message).trim(),fmt);
            } catch(DateTimeParseException e) {
                System.out.println("Use yyyy-MM-dd HH:mm.");
            }
        }
    }
}

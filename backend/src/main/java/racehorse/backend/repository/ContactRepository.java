package racehorse.backend.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * V63: insurers the club accepts and each owner's list of people who may decide for
 * their horses (contract clause 8). Chosen from a list instead of typed.
 */
@Repository
public class ContactRepository {

    private final JdbcClient jdbc;

    public ContactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Insurer(int insurerId, String name, String emergencyPhone) {}

    public record Person(int contactId, int ownerId, String fullName, String phone, String relationship) {}

    public List<Insurer> listInsurers() {
        return jdbc.sql("SELECT insurer_id, name, emergency_phone FROM INSURER WHERE active = 1 ORDER BY sort_order, name")
                .query((rs, n) -> new Insurer(rs.getInt("insurer_id"), rs.getString("name"), rs.getString("emergency_phone")))
                .list();
    }

    /** The insurer and number given are one of the listed insurers. */
    public boolean isListedInsurer(String name, String phone) {
        return jdbc.sql("SELECT COUNT(*) FROM INSURER WHERE active = 1 AND name = :name AND emergency_phone = :phone")
                .param("name", name).param("phone", phone)
                .query(Integer.class).single() > 0;
    }

    public List<Person> listPeople(int ownerId) {
        return jdbc.sql("""
                SELECT contact_id, owner_id, full_name, phone, relationship FROM OWNER_CONTACT
                WHERE owner_id = :owner ORDER BY full_name
                """)
                .param("owner", ownerId)
                .query((rs, n) -> new Person(rs.getInt("contact_id"), rs.getInt("owner_id"), rs.getString("full_name"),
                        rs.getString("phone"), rs.getString("relationship")))
                .list();
    }

    /** The person and number given are on this owner's list. */
    public boolean isOwnersPerson(int ownerId, String name, String phone) {
        return jdbc.sql("SELECT COUNT(*) FROM OWNER_CONTACT WHERE owner_id = :owner AND full_name = :name AND phone = :phone")
                .param("owner", ownerId).param("name", name).param("phone", phone)
                .query(Integer.class).single() > 0;
    }

    public Optional<Person> findPerson(int ownerId, String name, String phone) {
        return jdbc.sql("""
                SELECT contact_id, owner_id, full_name, phone, relationship FROM OWNER_CONTACT
                WHERE owner_id = :owner AND full_name = :name AND phone = :phone
                """)
                .param("owner", ownerId).param("name", name).param("phone", phone)
                .query((rs, n) -> new Person(rs.getInt("contact_id"), rs.getInt("owner_id"), rs.getString("full_name"),
                        rs.getString("phone"), rs.getString("relationship")))
                .optional();
    }

    public int addPerson(int ownerId, String name, String phone, String relationship) {
        return jdbc.sql("""
                INSERT INTO OWNER_CONTACT (owner_id, full_name, phone, relationship)
                OUTPUT INSERTED.contact_id
                VALUES (:owner, :name, :phone, :relationship)
                """)
                .param("owner", ownerId).param("name", name).param("phone", phone).param("relationship", relationship)
                .query(Integer.class).single();
    }
}

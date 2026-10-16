package com.elearning.service;

import com.elearning.entity.Role;
import com.elearning.entity.SchoolEnrollment;
import com.elearning.entity.User;
import com.elearning.repository.SchoolEnrollmentRepository;
import com.elearning.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Modification et suppression, par l'administrateur, d'un compte étudiant ou professeur.
 * Une suppression n'efface jamais un historique : un compte qui a des résultats, des paiements
 * ou des contenus enseignés se désactive plutôt.
 */
@Service
@RequiredArgsConstructor
public class AdminAccountService {

    static final List<String> LEVELS = List.of("L1", "L2", "L3", "M1", "M2");

    private final UserRepository userRepository;
    private final SchoolEnrollmentRepository enrollmentRepository;
    private final SchoolService schoolService;
    private final SchoolAdmissionService admissionService;
    private final EmailDomainChecker emailDomainChecker;
    private final EntityManager em;

    public record AccountUpdate(String firstName, String lastName, String email, String phone,
                                String level, String specialization, String birthDate, String birthPlace,
                                String subjects) {}

    @Transactional
    public User update(Long id, AccountUpdate in) {
        User user = studentOrTeacher(id);
        user.setFirstName(required(in.firstName(), "Le prénom"));
        user.setLastName(required(in.lastName(), "Le nom"));
        user.setPhone(blank(in.phone()) ? null : ContactValidator.phone(in.phone(), ContactValidator.Usage.ANY));

        if (user.getRole() == Role.ROLE_STUDENT) {
            String level = required(in.level(), "Le niveau").toUpperCase();
            if (!LEVELS.contains(level)) throw new IllegalArgumentException("Niveau invalide : choisissez L1, L2, L3, M1 ou M2.");
            user.setLevel(level);
            user.setSpecialization(blank(in.specialization()) ? null : in.specialization().trim());
            user.setBirthPlace(required(in.birthPlace(), "Le lieu de naissance"));
            LocalDate birthDate;
            try {
                birthDate = LocalDate.parse(required(in.birthDate(), "La date de naissance"));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Date de naissance invalide.");
            }
            if (birthDate.isAfter(LocalDate.now())) throw new IllegalArgumentException("La date de naissance ne peut pas être dans le futur.");
            user.setBirthDate(birthDate);
        } else {
            user.setSubjects(required(in.subjects(), "Au moins une matière"));
        }

        // L'email n'est vérifié (format, domaine) que s'il change : l'adresse actuelle reste valable telle quelle
        String requested = required(in.email(), "L'email");
        if (!requested.equalsIgnoreCase(user.getEmail())) {
            String email = emailDomainChecker.check(requested);
            if (userRepository.findByEmail(email).isPresent()) {
                throw new IllegalArgumentException("Un compte existe déjà avec l'email " + email + ".");
            }
            // Devoirs, examens et classes virtuelles retrouvent l'étudiant par son email
            String old = user.getEmail();
            for (String entity : List.of("ExamStudent", "QcmStudent", "VirtualClassStudent")) {
                em.createQuery("update " + entity + " s set s.studentEmail = :email where lower(s.studentEmail) = lower(:old)")
                    .setParameter("email", email).setParameter("old", old).executeUpdate();
            }
            em.createQuery("update VirtualClassRollCallAnswer a set a.email = :email where lower(a.email) = lower(:old)")
                .setParameter("email", email).setParameter("old", old).executeUpdate();
            user.setEmail(email);
        }
        return userRepository.save(user);
    }

    @Transactional(rollbackFor = IOException.class)
    public void delete(Long id) throws IOException {
        User user = studentOrTeacher(id);
        String name = user.getFirstName() + " " + user.getLastName();
        List<String> kept = new ArrayList<>();

        if (user.getRole() == Role.ROLE_TEACHER) {
            if (count("select count(c) from Course c where c.teacher = :u", user) > 0) kept.add("des cours");
            if (count("select count(l) from Lesson l where l.teacher = :u", user) > 0) kept.add("des leçons");
            if (count("select count(e) from Exam e where e.professor = :u", user) > 0) kept.add("des examens");
            if (count("select count(q) from Qcm q where q.professor = :u", user) > 0) kept.add("des devoirs");
            if (count("select count(v) from VirtualClass v where v.teacher = :u", user) > 0) kept.add("des classes virtuelles");
        } else {
            if (count("select count(p) from QcmPassage p where p.student = :u", user) > 0
                || em.createQuery("select count(s) from ExamStudent s where lower(s.studentEmail) = lower(:email) and s.submission is not null", Long.class)
                    .setParameter("email", user.getEmail()).getSingleResult() > 0) {
                kept.add("des résultats de devoirs ou d'examens");
            }
        }
        List<SchoolEnrollment> enrollments = enrollmentRepository.findByStudentOrderByAcademicYearDesc(user);
        for (SchoolEnrollment e : enrollments) {
            try {
                schoolService.assertDeletable(e);
            } catch (IllegalArgumentException ex) {
                kept.add("des paiements ou attestations de scolarité");
                break;
            }
        }
        if (!kept.isEmpty()) {
            throw new IllegalArgumentException(name + " a " + String.join(", ", kept)
                + " : le compte ne peut pas être supprimé sans perdre cet historique. Désactivez-le plutôt.");
        }

        // Données sans valeur d'historique : invitations sans copie rendue, notifications, notes personnelles
        for (String entity : List.of("ExamStudent", "QcmStudent", "VirtualClassStudent")) {
            em.createQuery("delete from " + entity + " s where lower(s.studentEmail) = lower(:email)"
                    + ("ExamStudent".equals(entity) ? " and s.submission is null" : ""))
                .setParameter("email", user.getEmail()).executeUpdate();
        }
        for (String entity : List.of("Notification", "Reference", "BookAnnotation", "VirtualClassAttendance")) {
            em.createQuery("delete from " + entity + " x where x.user = :u").setParameter("u", user).executeUpdate();
        }
        for (SchoolEnrollment e : enrollments) admissionService.deleteEnrollment(e.getId());
        user.getEnrolledCourses().clear();
        userRepository.delete(user);
        userRepository.flush();
    }

    private User studentOrTeacher(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Compte introuvable."));
        if (user.getRole() != Role.ROLE_STUDENT && user.getRole() != Role.ROLE_TEACHER) {
            throw new IllegalArgumentException("Seuls les comptes étudiant ou professeur peuvent être modifiés ou supprimés.");
        }
        return user;
    }

    private long count(String jpql, User user) {
        return em.createQuery(jpql, Long.class).setParameter("u", user).getSingleResult();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String required(String value, String what) {
        if (blank(value)) throw new IllegalArgumentException(what + " est obligatoire.");
        return value.trim();
    }
}

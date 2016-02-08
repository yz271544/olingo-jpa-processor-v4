import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;

import org.apache.olingo.jpa.processor.core.testmodel.AdministrativeDivision;
import org.apache.olingo.jpa.processor.core.testmodel.BusinessPartnerRole;
import org.apache.olingo.jpa.processor.core.testmodel.DataSourceHelper;
import org.apache.olingo.jpa.processor.core.testmodel.Organization;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestCriteriaBuilder {
  protected static final String PUNIT_NAME = "org.apache.olingo.jpa";
  private static final String ENTITY_MANAGER_DATA_SOURCE = "javax.persistence.nonJtaDataSource";
  private static EntityManagerFactory emf;
  private EntityManager em;
  private CriteriaBuilder cb;

  @BeforeClass
  public static void setupClass() {
    Map<String, Object> properties = new HashMap<String, Object>();
    properties.put(ENTITY_MANAGER_DATA_SOURCE, DataSourceHelper.createDataSource(
        DataSourceHelper.DB_H2));
    emf = Persistence.createEntityManagerFactory(PUNIT_NAME, properties);
  }

  @Before
  public void setup() {
    em = emf.createEntityManager();
    cb = em.getCriteriaBuilder();
  }

  @Test
  public void testSubSelect() {
    // https://stackoverflow.com/questions/29719321/combining-conditional-expressions-with-and-and-or-predicates-using-the-jpa-c
    CriteriaQuery<Tuple> adminQ1 = cb.createTupleQuery();
    Subquery<Long> adminQ2 = adminQ1.subquery(Long.class);
    Subquery<Long> adminQ3 = adminQ2.subquery(Long.class);
    Subquery<Long> org = adminQ3.subquery(Long.class);

    Root<AdministrativeDivision> adminRoot1 = adminQ1.from(AdministrativeDivision.class);
    Root<AdministrativeDivision> adminRoot2 = adminQ2.from(AdministrativeDivision.class);
    Root<AdministrativeDivision> adminRoot3 = adminQ3.from(AdministrativeDivision.class);
    Root<Organization> org1 = org.from(Organization.class);

    org.where(cb.and(cb.equal(org1.get("ID"), "3")), createParentOrg(org1, adminRoot3));
    org.select(cb.literal(1L));

    adminQ3.where(cb.and(createParentAdmin(adminRoot3, adminRoot2), cb.exists(org)));
    adminQ3.select(cb.literal(1L));

    adminQ2.where(cb.and(createParentAdmin(adminRoot2, adminRoot1), cb.exists(adminQ3)));
    adminQ2.select(cb.literal(1L));

    adminQ1.where(cb.exists(adminQ2));
    adminQ1.multiselect(adminRoot1.get("divisionCode"));

    TypedQuery<Tuple> tq = em.createQuery(adminQ1);
    tq.getResultList();
  }

  @Test
  public void TestExpandCount() {
    CriteriaQuery<Tuple> count = cb.createTupleQuery();
    Root<?> roles = count.from(BusinessPartnerRole.class);

    count.multiselect(roles.get("businessPartnerID"), cb.count(roles).alias("$count"));
    count.groupBy(roles.get("businessPartnerID"));
    count.orderBy(cb.desc(cb.count(roles)));
    TypedQuery<Tuple> tq = em.createQuery(count);
    List<Tuple> act = tq.getResultList();
    tq.getFirstResult();
  }

  @Test
  public void TestAnd() {
    CriteriaQuery<Tuple> count = cb.createTupleQuery();
    Root<?> adminDiv = count.from(AdministrativeDivision.class);

    count.multiselect(adminDiv);
    Predicate[] restrictions = new Predicate[3];
    restrictions[0] = cb.equal(adminDiv.get("codeID"), "NUTS2");
    restrictions[1] = cb.equal(adminDiv.get("divisionCode"), "BE34");
    restrictions[2] = cb.equal(adminDiv.get("codePublisher"), "Eurostat");
    count.where(cb.and(restrictions));
    TypedQuery<Tuple> tq = em.createQuery(count);
    List<Tuple> act = tq.getResultList();
    tq.getFirstResult();
  }

  private Expression<Boolean> createParentAdmin(Root<AdministrativeDivision> subQuery,
      Root<AdministrativeDivision> query) {
    return cb.and(cb.equal(query.get("codePublisher"), subQuery.get("codePublisher")),
        cb.and(cb.equal(query.get("codeID"), subQuery.get("parentCodeID")),
            cb.equal(query.get("divisionCode"), subQuery.get("parentDivisionCode"))));
  }

  private Predicate createParentOrg(Root<Organization> org1, Root<AdministrativeDivision> adminRoot3) {
    return cb.and(cb.equal(adminRoot3.get("codePublisher"), org1.get("address").get("regionCodePublisher")),
        cb.and(cb.equal(adminRoot3.get("codeID"), org1.get("address").get("regionCodeID")),
            cb.equal(adminRoot3.get("divisionCode"), org1.get("address").get("region"))));
  }

}

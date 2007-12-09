package test;

import org.junit.Test;
import static org.junit.Assert.*;

import thewebsemantic.Bean2RDF;
import thewebsemantic.Id;
import thewebsemantic.RDF2Bean;

import com.hp.hpl.jena.ontology.Individual;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.rdf.model.ModelFactory;

public class TestIdStuff {
	@Test
	public void testId() {
		String id = "myuniqueid";
		TestIDBean bean = new TestIDBean(id);
		bean.setAddress("123 Oak Circle");
		bean.setAge(32);
		OntModel m = ModelFactory.createOntologyModel();	
		Bean2RDF writer = new Bean2RDF(m);
		writer.write(bean);
		
		RDF2Bean reader = new RDF2Bean(m);
		TestIDBean bean2 = reader.load(TestIDBean.class, id);
		assertEquals("123 Oak Circle", bean2.getAddress());
		assertEquals(32, bean2.getAge());
	}
	
	class Flute {
	   private String id;
	   public int i = 0;
	   public Flute(String id) {
	      this.id = id;
	      i++;
	   }
	   
	   @Id
	   public String getMyId() {
	      return id;
	   }
	}
	
	/**
	 * jenabean should apply the id to the 
	 * constructor.
	 */
	public void testConstructor() {
       OntModel m = ModelFactory.createOntologyModel();    
       Bean2RDF writer = new Bean2RDF(m);
       writer.write( new Flute("a"));
    
       RDF2Bean reader = new RDF2Bean(m);
       Flute a = reader.load(Flute.class, "a");
       assertEquals("a", a.getMyId());
       assertEquals(1, a.i);
       // its uri should be http://package/classname/id
       Individual i = m.getIndividual("http://test/Flute/a");
       assertNotNull(i);
	}
}

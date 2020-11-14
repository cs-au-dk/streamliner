package dk.casa.streamliner.other.examples;

public final class Employee {
	private Integer id;
	private String name;
	private Double salary;

	public Employee(Integer id, String name, Double salary) {
		this.id = id;
		this.name = name;
		this.salary = salary;
	}

	public void setSalary(Double salary) {
		this.salary = salary;
	}

	public void salaryIncrement(Double percentage) {
		Double newSalary = salary + percentage * salary / 100;
		setSalary(newSalary);
	}

	public String toString() {
		return "Id: " + id + " Name:" + name + " Price:" + salary;
	}
}
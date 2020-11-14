package dk.casa.streamliner.other.adaptedcases;

import dk.casa.streamliner.stream.PushStream;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// Source: https://github.com/eugenp/tutorials/blob/master/guest/core-java/src/test/java/com/stackify/stream/EmployeeTest.java

final class Employee {
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

public class EmployeeTest {
	public static void whenIncrementSalaryUsingPeek_thenApplyNewSalary(Employee[] arrayOfEmps) {
		List<Employee> empList = Arrays.asList(arrayOfEmps);

		List<Employee> res = PushStream.of(empList)
				.peek(e -> e.salaryIncrement(10.0))
				.peek(System.out::println)
				.collect(Collectors.toList());
	}

	public static void main(String[] args) {
		Employee[] arrayOfEmps = {
				new Employee(1, "Jeff Bezos", 100000.0),
				new Employee(2, "Bill Gates", 200000.0),
				new Employee(3, "Mark Zuckerberg", 300000.0)
		};
		whenIncrementSalaryUsingPeek_thenApplyNewSalary(arrayOfEmps);
	}
}

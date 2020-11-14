package dk.casa.streamliner.other.examples;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// Source: https://github.com/eugenp/tutorials/blob/master/guest/core-java/src/test/java/com/stackify/stream/EmployeeTest.java

public class EmployeeTest {
	public static void whenIncrementSalaryUsingPeek_thenApplyNewSalary() {
		Employee[] arrayOfEmps = {
				new Employee(1, "Jeff Bezos", 100000.0),
				new Employee(2, "Bill Gates", 200000.0),
				new Employee(3, "Mark Zuckerberg", 300000.0)
		};

		List<Employee> empList = Arrays.asList(arrayOfEmps);

		empList = empList.stream()
				.peek(e -> e.salaryIncrement(10.0))
				.peek(System.out::println)
				.collect(Collectors.toList());
	}

	public static void main(String[] args) {
		whenIncrementSalaryUsingPeek_thenApplyNewSalary();
	}
}

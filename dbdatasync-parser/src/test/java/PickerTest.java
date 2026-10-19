import org.dbdatasync.parser.model.Picker;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.sdk.enums.FilterEnum;
import org.dbdatasync.sdk.filter.impl.StringFilter;

import org.junit.Test;

public class PickerTest {

    @Test
    public void testCompareValueWithFilter() {
        Picker picker = new Picker(new TableGroup());
        // 比较条件：name != A
        StringFilter filter = new StringFilter("name", FilterEnum.NOT_EQUAL, "A", false);

        boolean equal = picker.compareValueWithFilter(filter, "A");
        assert !equal;

        boolean notEqual = picker.compareValueWithFilter(filter, "B");
        assert notEqual;

        notEqual = picker.compareValueWithFilter(filter, null);
        assert notEqual;
    }

}

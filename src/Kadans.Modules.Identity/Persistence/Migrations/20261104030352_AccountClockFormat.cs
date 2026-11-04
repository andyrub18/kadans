using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Identity.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AccountClockFormat : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "clock_format",
                schema: "identity",
                table: "asp_net_users",
                type: "character varying(16)",
                maxLength: 16,
                nullable: false,
                // Every account so far has read 24-hour times: it keeps them until changed in Settings.
                defaultValue: "TwentyFourHour");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "clock_format",
                schema: "identity",
                table: "asp_net_users");
        }
    }
}
